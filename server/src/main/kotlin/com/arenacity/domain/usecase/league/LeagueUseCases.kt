package com.arenacity.domain.usecase.league

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.*
import com.arenacity.domain.repository.*
import com.arenacity.domain.rules.LeagueRules
import com.arenacity.domain.rules.Schedule
import com.arenacity.domain.rules.StandingRow
import com.arenacity.domain.rules.Standings
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.engine.model.MatchResult
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class LeagueView(val league: League, val table: List<StandingRow>, val clubs: Map<UUID, Club>, val nextRoundAt: Instant?, val memberCount: Int)
data class FixturesView(val fixtures: List<Fixture>, val clubs: Map<UUID, Club>)
data class MatchView(val fixture: Fixture, val result: MatchdayResult?, val clubs: Map<UUID, Club>, val athleteNames: Map<String, String> = emptyMap())

class LeagueQueries(private val leagues: LeagueRepository, private val fixtures: FixtureRepository, private val clubs: ClubRepository, private val athletes: AthleteRepository, private val clock: Clock,
                    private val heroes: com.arenacity.domain.repository.HeroRepository? = null) {
    suspend fun view(league: League): LeagueView {
        val members = leagues.members(league.id)
        val played = when (league.kind) {
            LeagueKind.PUBLIC -> fixtures.listByLeagueSeason(league.id, league.seasonNumber)
            LeagueKind.PRIVATE -> fixtures.listPlayedByClubsSince(members, league.seasonStart.atStartOfDay(ZoneId.of(league.timezone)).toInstant())
        }
        val next = if (league.kind == LeagueKind.PUBLIC && league.currentRound < LeagueRules.ROUNDS) Schedule.roundInstant(league.seasonStart, league.currentRound + 1, league.timezone, league.matchdayHour) else null
        return LeagueView(league, Standings.table(played, members), clubs.findByIds(members).associateBy { it.id }, next, members.size)
    }

    suspend fun mine(clubId: UUID): Pair<LeagueView?, List<LeagueView>> {
        val all = leagues.listForClub(clubId)
        return all.firstOrNull { it.kind == LeagueKind.PUBLIC && it.status == LeagueStatus.ACTIVE }?.let { view(it) } to all.filter { it.kind == LeagueKind.PRIVATE }.map { view(it) }
    }

    suspend fun clubFixtures(clubId: UUID): Pair<Fixture?, FixturesView> {
        val all = fixtures.listByClub(clubId, null, 200)
        val next = all.filter { it.status == FixtureStatus.SCHEDULED }.minByOrNull { it.scheduledAt }
        val recent = all.filter { it.status == FixtureStatus.PLAYED }.sortedByDescending { it.playedAt }.take(5)
        return next to FixturesView(recent, clubsOf(recent + listOfNotNull(next)))
    }

    suspend fun match(fixtureId: UUID): MatchView {
        val f = fixtures.findById(fixtureId) ?: throw NotFoundException("Match not found")
        val result = f.resultJson?.let { MatchdayResult.parse(it) }
        return MatchView(f, result, clubsOf(listOf(f)), athleteNames(f))
    }

    /** The scorers' names: the clubs' athletes and their heroes under their match ids (rules.HeroSquad). */
    suspend fun athleteNames(f: Fixture): Map<String, String> {
        val ids = listOf(f.homeClubId, f.awayClubId)
        val heroNames = heroes?.let { h -> ids.flatMap { c -> com.arenacity.domain.rules.HeroSquad.names(c, h.listByClub(c)).entries } }.orEmpty()
        return (athletes.namesByClubs(ids) + heroNames.associate { it.key to it.value }).mapKeys { it.key.toString() }
    }

    private suspend fun clubsOf(list: List<Fixture>) = clubs.findByIds(list.flatMap { listOf(it.homeClubId, it.awayClubId) }.toSet()).associateBy { it.id }
}

class CreatePrivateLeagueUseCase(private val service: ClubService, private val leagues: LeagueRepository, private val clock: Clock) {
    suspend operator fun invoke(userId: UUID, name: String): League {
        val club = service.clubOf(userId)
        if (name.trim().length !in 3..24) throw ValidationException("League name must be 3..24 characters")
        val now = clock.now()
        var code: String
        var attempt = 0
        do { code = LeagueRules.code(now.toEpochMilli() + attempt * 7919L + club.id.hashCode()); attempt++ } while (leagues.findByCode(code) != null && attempt < 20)
        val league = leagues.create(League(UUID.randomUUID(), LeagueKind.PRIVATE, name.trim(), code, 0, club.tzBucket, club.timezone, LeagueRules.MATCHDAY_HOUR, 1, now.atZone(ZoneId.of(club.timezone)).toLocalDate(), 0, club.id, now, now))
        leagues.addMember(league.id, club.id)
        return league
    }
}

class JoinPrivateLeagueUseCase(
    private val service: ClubService, private val leagues: LeagueRepository, private val tx: Tx = NoTx,
    private val invites: com.arenacity.domain.usecase.social.InviteTracker? = null, private val chat: com.arenacity.domain.usecase.social.ChatService? = null,
) {
    suspend operator fun invoke(userId: UUID, code: String): League = tx {
        val club = service.clubOf(userId)
        val found = leagues.findByCode(code.trim().uppercase())?.takeIf { it.kind == LeagueKind.PRIVATE } ?: throw NotFoundException("League code not found")
        val league = leagues.lockById(found.id) ?: throw NotFoundException("League code not found")
        val members = leagues.members(league.id)
        if (club.id in members) throw ConflictException("Already a member")
        if (members.size >= LeagueRules.PRIVATE_MAX) throw ConflictException("League is full")
        leagues.addMember(league.id, club.id)
        chat?.system(league.id, "join", club.name)
        runCatching { invites?.leagueJoined(league, club) }
        league
    }
}

class PlayFriendlyUseCase(private val service: ClubService, private val clubs: ClubRepository, private val fixtures: FixtureRepository, private val bots: BotService, private val runner: MatchdayRunner, private val queries: LeagueQueries, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, opponentClubId: UUID?): MatchView = tx {
        val club = service.lockedClubOf(userId)
        val now = clock.now()
        val today = now.atZone(ZoneId.of(club.timezone)).toLocalDate()
        val playedToday = if (club.friendliesDate == today) club.friendliesPlayed else 0
        if (playedToday >= LeagueRules.FRIENDLIES_PER_DAY) throw ConflictException("Daily friendly limit reached")
        val opponent = when {
            opponentClubId == null -> clubs.listBots(club.tzBucket).minByOrNull { it.id } ?: bots.createBot(club.tzBucket, club.division, club.timezone, 0)
            opponentClubId == club.id -> throw ValidationException("Cannot play against yourself")
            else -> clubs.findById(opponentClubId) ?: throw NotFoundException("Opponent not found")
        }
        // a friendly costs energy (league matchday matches do not)
        val paid = com.arenacity.domain.usecase.club.EnergyUseCases.spend(club, com.arenacity.domain.rules.Energy.FRIENDLY, now)
        clubs.update(paid.copy(friendliesDate = today, friendliesPlayed = playedToday + 1, updatedAt = now))
        val fixture = Fixture(UUID.randomUUID(), null, FixtureKind.FRIENDLY, 0, 0, now, club.id, opponent.id, FixtureStatus.SCHEDULED, null, null, now.toEpochMilli() xor club.id.leastSignificantBits, null, null, now)
        fixtures.createAll(listOf(fixture))
        val p = runner.playFixture(fixture, now) ?: throw ConflictException("Friendly could not be played")
        MatchView(p.fixture, p.result, mapOf(club.id to club, opponent.id to opponent), queries.athleteNames(p.fixture))
    }
}
