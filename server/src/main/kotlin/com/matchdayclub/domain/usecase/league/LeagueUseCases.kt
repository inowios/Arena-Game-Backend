package com.matchdayclub.domain.usecase.league

import com.matchdayclub.common.time.Clock
import com.matchdayclub.common.tx.NoTx
import com.matchdayclub.common.tx.Tx
import com.matchdayclub.domain.exception.ConflictException
import com.matchdayclub.domain.exception.NotFoundException
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.repository.*
import com.matchdayclub.domain.rules.LeagueRules
import com.matchdayclub.domain.rules.Schedule
import com.matchdayclub.domain.rules.StandingRow
import com.matchdayclub.domain.rules.Standings
import com.matchdayclub.domain.usecase.club.ClubService
import com.matchdayclub.engine.model.MatchResult
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class LeagueView(val league: League, val table: List<StandingRow>, val clubs: Map<UUID, Club>, val nextRoundAt: Instant?, val memberCount: Int)
data class FixturesView(val fixtures: List<Fixture>, val clubs: Map<UUID, Club>)
data class MatchView(val fixture: Fixture, val result: MatchdayResult?, val clubs: Map<UUID, Club>, val athleteNames: Map<String, String> = emptyMap())

class LeagueQueries(private val leagues: LeagueRepository, private val fixtures: FixtureRepository, private val clubs: ClubRepository, private val athletes: AthleteRepository, private val clock: Clock) {
    suspend fun view(leagueId: UUID): LeagueView = view(leagues.findById(leagueId) ?: throw NotFoundException("League not found"))

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

    suspend fun fixtures(leagueId: UUID, round: Int?): FixturesView {
        val league = leagues.findById(leagueId) ?: throw NotFoundException("League not found")
        val list = fixtures.listByLeagueSeason(league.id, league.seasonNumber, round)
        return FixturesView(list, clubsOf(list))
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

    suspend fun athleteNames(f: Fixture): Map<String, String> =
        (athletes.listByClub(f.homeClubId) + athletes.listByClub(f.awayClubId)).associate { it.id.toString() to it.name }

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
    private val invites: com.matchdayclub.domain.usecase.social.InviteTracker? = null, private val chat: com.matchdayclub.domain.usecase.social.ChatService? = null,
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

class LeavePrivateLeagueUseCase(private val service: ClubService, private val leagues: LeagueRepository, private val tx: Tx = NoTx, private val chat: com.matchdayclub.domain.usecase.social.ChatService? = null) {
    suspend operator fun invoke(userId: UUID, leagueId: UUID) = tx {
        val club = service.clubOf(userId)
        val league = leagues.lockById(leagueId)?.takeIf { it.kind == LeagueKind.PRIVATE } ?: throw NotFoundException("League not found")
        leagues.removeMember(league.id, club.id)
        // the creator leaving hands the league to the longest-standing member
        if (league.createdBy == club.id) leagues.members(league.id).firstOrNull()?.let { heir -> leagues.update(league.copy(createdBy = heir, updatedAt = java.time.Instant.now())) }
        chat?.system(league.id, "leave", club.name)
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
        clubs.update(club.copy(friendliesDate = today, friendliesPlayed = playedToday + 1, updatedAt = now))
        val fixture = Fixture(UUID.randomUUID(), null, FixtureKind.FRIENDLY, 0, 0, now, club.id, opponent.id, FixtureStatus.SCHEDULED, null, null, now.toEpochMilli() xor club.id.leastSignificantBits, null, null, now)
        fixtures.createAll(listOf(fixture))
        val p = runner.playFixture(fixture, now) ?: throw ConflictException("Friendly could not be played")
        MatchView(p.fixture, p.result, mapOf(club.id to club, opponent.id to opponent), queries.athleteNames(p.fixture))
    }
}

data class PublicClub(val club: Club, val squadOverall: Int, val pitchLevel: Int, val sections: List<Sport> = listOf(Sport.FOOTBALL), val sectionOverall: Map<Sport, Int> = emptyMap())

class GetPublicClubUseCase(private val clubs: ClubRepository, private val athletes: AthleteRepository, private val buildings: BuildingRepository, private val sections: com.matchdayclub.domain.usecase.athlete.SectionService? = null) {
    suspend operator fun invoke(clubId: UUID): PublicClub {
        val club = clubs.findById(clubId) ?: throw NotFoundException("Club not found")
        val squad = athletes.listByClub(clubId)
        val bs = buildings.listByClub(clubId)
        val pitch = bs.firstOrNull { it.type == com.matchdayclub.domain.rules.BuildingType.PITCH }?.level ?: 1
        val active = sections?.active(club, squad, bs) ?: listOf(Sport.FOOTBALL)
        fun overallOf(s: Sport) = squad.filter { it.sport == s }.sortedByDescending { it.overall }.take(com.matchdayclub.domain.rules.Sections.profile(s).startersCount).map { it.overall }.let { if (it.isEmpty()) 0 else it.average().toInt() }
        return PublicClub(club, overallOf(Sport.FOOTBALL), pitch, active, active.associateWith(::overallOf))
    }
}
