package com.matchdayclub.domain.usecase.league

import com.matchdayclub.common.time.Clock
import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.repository.*
import com.matchdayclub.domain.rules.AthleteFactory
import com.matchdayclub.domain.rules.LeagueRules
import com.matchdayclub.domain.rules.Schedule
import com.matchdayclub.domain.usecase.athlete.EngineBridge
import com.matchdayclub.domain.usecase.athlete.LineupRules
import com.matchdayclub.domain.usecase.club.ClubInitializer
import java.time.Instant
import java.util.UUID

/** Creates AI-controlled clubs that fill public leagues. */
class BotService(private val users: UserRepository, private val clubs: ClubRepository, private val athletes: AthleteRepository, private val clock: Clock) {
    private val names = listOf("Nordwind", "Riverside", "Ironbridge", "Sunset United", "Harbor City", "Granite Rovers", "Oakfield", "Steel Valley", "Blue Lagoon", "Red Canyon", "Silver Lake", "Pine Hill", "Westgate", "Falcon Park", "Lakeside", "Copper Town", "Highland", "Amber Coast", "Stone Bridge", "Golden Bay", "Maple Grove", "Northport", "Eastwood", "Summit")
    private val palette = listOf("#C8102E" to "#FFFFFF", "#003399" to "#FFD700", "#006400" to "#FFFFFF", "#000000" to "#FFFFFF", "#FF6600" to "#000000", "#800080" to "#FFFFFF", "#1E90FF" to "#FFFFFF", "#8B0000" to "#F5F5DC")

    suspend fun createBot(tzBucket: Int, division: Int, timezone: String, index: Int): Club {
        val now = clock.now()
        val userId = UUID.randomUUID()
        users.create(User(userId, AuthProvider.BOT, "bot-$userId", null, null, null, true, now, now))
        val clubId = UUID.randomUUID()
        val (p, s) = palette[index % palette.size]
        val club = clubs.create(Club(clubId, userId, "FC ${names[index % names.size]}", p, s, null, 1, 0, 0, 0, timezone, null, null, now, now, isBot = true, division = division, tzBucket = tzBucket))
        athletes.createAll(AthleteFactory.squad(clubId, now, 45, 58))
        return club
    }
}

/** Public-league membership and fixture generation. */
class LeagueService(private val leagues: LeagueRepository, private val fixtures: FixtureRepository, private val bots: BotService, private val clock: Clock) {
    suspend fun ensurePublicMembership(club: Club): League {
        leagues.listForClub(club.id).firstOrNull { it.kind == LeagueKind.PUBLIC }?.let { return it }
        val now = clock.now()
        val league = leagues.findOpenPublic(club.tzBucket, club.division, LeagueRules.PUBLIC_SIZE) ?: leagues.create(
            League(UUID.randomUUID(), LeagueKind.PUBLIC, "Division ${club.division}", null, club.division, club.tzBucket, club.timezone, LeagueRules.MATCHDAY_HOUR, 1, LeagueRules.firstSeasonStart(club.timezone, now), 0, null, now, now),
        )
        leagues.addMember(league.id, club.id)
        return league
    }

    /** Fills the league with bots up to 8 and generates the season's fixtures (idempotent). */
    suspend fun prepareSeason(league: League): List<Fixture> {
        val existing = fixtures.listByLeagueSeason(league.id, league.seasonNumber)
        if (existing.isNotEmpty()) return existing
        var members = leagues.members(league.id)
        var i = 0
        while (members.size < LeagueRules.PUBLIC_SIZE) {
            val bot = bots.createBot(league.tzBucket, league.division, league.timezone, (members.size * 7 + i++ + league.id.hashCode()).mod(24))
            leagues.addMember(league.id, bot.id); members = members + bot.id
        }
        val seed = league.id.leastSignificantBits xor league.seasonNumber.toLong()
        val now = clock.now()
        val list = Schedule.rounds(members, seed).flatMapIndexed { idx, pairs ->
            val round = idx + 1
            val at = Schedule.roundInstant(league.seasonStart, round, league.timezone, league.matchdayHour)
            pairs.map { (h, a) -> Fixture(UUID.randomUUID(), league.id, FixtureKind.LEAGUE, league.seasonNumber, round, at, h, a, FixtureStatus.SCHEDULED, null, null, (h.leastSignificantBits xor a.mostSignificantBits xor seed xor round.toLong()), null, null, now) }
        }
        fixtures.createAll(list)
        return list
    }
}

class JoinPublicLeagueInitializer(private val clubs: ClubRepository, private val leagueService: LeagueService, private val clock: Clock) : ClubInitializer {
    override suspend fun onClubCreated(club: Club) {
        val bucketed = clubs.update(club.copy(tzBucket = LeagueRules.tzBucket(club.timezone, clock.now())))
        leagueService.ensurePublicMembership(bucketed)
    }
}

/** "Stored or default" lineup by club id, for the simulator. */
class LineupService(private val athletes: AthleteRepository, private val lineups: LineupRepository) {
    suspend fun engineLineup(club: Club, now: Instant): Pair<com.matchdayclub.engine.model.Lineup, List<Athlete>> {
        val squad = athletes.listByClub(club.id)
        val stored = lineups.find(club.id, Sport.FOOTBALL)?.takeIf { runCatching { LineupRules.validate(it.starters, it.bench, squad) }.isSuccess }
        val lineup = stored ?: LineupRules.default(club.id, Sport.FOOTBALL, squad, now)
        return EngineBridge.toEngineLineup(club.id.toString(), lineup, squad, now) to squad
    }
}
