package com.arenacity.domain.usecase.league

import com.arenacity.common.time.Clock
import com.arenacity.domain.model.*
import com.arenacity.domain.repository.*
import com.arenacity.domain.rules.AthleteFactory
import com.arenacity.domain.rules.LeagueRules
import com.arenacity.domain.rules.Schedule
import com.arenacity.domain.usecase.athlete.EngineBridge
import com.arenacity.domain.usecase.athlete.LineupRules
import com.arenacity.domain.usecase.club.ClubInitializer
import java.time.Instant
import java.util.UUID

/** Bot squad quality band by division: the entry division is beatable with a starter squad, the top one is not. */
/** Bot athletes by division (also the arena ladder's opposition): 40..52 at the bottom, 82..94 at the top. */
fun botQuality(division: Int): Pair<Int, Int> =
    if (division >= com.arenacity.domain.rules.Arenas.LEGEND_DIVISION) 89 to 99
    else (40 + (division - 1) * 7).coerceAtMost(82) to (52 + (division - 1) * 7).coerceAtMost(94)

/** Creates AI-controlled clubs that fill public leagues. */
class BotService(private val users: UserRepository, private val clubs: ClubRepository, private val athletes: AthleteRepository, private val clock: Clock, private val leagues: LeagueRepository? = null) {
    /** A bot of this bucket/division that is not in any active public league (finished seasons free their bots), else a new one. */
    suspend fun acquire(tzBucket: Int, division: Int, timezone: String, index: Int, takenNames: Set<String>): Club {
        if (leagues != null) {
            val busy = leagues.activePublicMembers(tzBucket, division)
            clubs.listBots(tzBucket).firstOrNull { it.division == division && it.id !in busy && it.name !in takenNames }?.let { return it }
        }
        var i = index
        var bot = createBot(tzBucket, division, timezone, i)
        var guard = 0
        while (bot.name in takenNames && guard++ < names.size) { i++; bot = clubs.update(bot.copy(name = "FC ${names[i % names.size]}")) }
        return bot
    }

    /** A new bot whose name is not in [takenNames] (cups need 16 distinct names). */
    suspend fun createUnique(tzBucket: Int, division: Int, timezone: String, takenNames: Set<String>, start: Int = 0): Club {
        val free = (0 until names.size).map { (start + it) % names.size }.firstOrNull { "FC ${names[it]}" !in takenNames }
        val bot = createBot(tzBucket, division, timezone, free ?: start)
        if (free != null) return bot
        var n = 2
        while ("${bot.name} $n" in takenNames) n++
        return clubs.update(bot.copy(name = "${bot.name} $n"))
    }

    private val names = listOf("Nordwind", "Riverside", "Ironbridge", "Sunset United", "Harbor City", "Granite Rovers", "Oakfield", "Steel Valley", "Blue Lagoon", "Red Canyon", "Silver Lake", "Pine Hill", "Westgate", "Falcon Park", "Lakeside", "Copper Town", "Highland", "Amber Coast", "Stone Bridge", "Golden Bay", "Maple Grove", "Northport", "Eastwood", "Summit")
    private val palette = listOf("#C8102E" to "#FFFFFF", "#003399" to "#FFD700", "#006400" to "#FFFFFF", "#000000" to "#FFFFFF", "#FF6600" to "#000000", "#800080" to "#FFFFFF", "#1E90FF" to "#FFFFFF", "#8B0000" to "#F5F5DC")

    suspend fun createBot(tzBucket: Int, division: Int, timezone: String, index: Int): Club {
        val now = clock.now()
        val userId = UUID.randomUUID()
        users.create(User(userId, AuthProvider.BOT, "bot-$userId", null, null, null, true, now, now))
        val clubId = UUID.randomUUID()
        val (p, s) = palette[index % palette.size]
        val club = clubs.create(Club(clubId, userId, "FC ${names[index % names.size]}", p, s, null, 1, 0, 0, 0, timezone, null, null, now, now, isBot = true, division = division, tzBucket = tzBucket,
            kit = com.arenacity.domain.rules.Cosmetics.botKit(clubId.leastSignificantBits).takeUnless { com.arenacity.domain.rules.Cosmetics.isDefault(it) }))
        val (lo, hi) = botQuality(division)
        for (sport in com.arenacity.domain.rules.Sections.botSections(division)) athletes.createAll(AthleteFactory.squad(clubId, now, lo, hi, sport))
        return club
    }
}

/** Public-league membership and fixture generation. Leagues are always full (humans + bots) so tables and fixtures exist from day one. */
class LeagueService(private val leagues: LeagueRepository, private val fixtures: FixtureRepository, private val clubs: ClubRepository, private val bots: BotService, private val clock: Clock) {
    suspend fun ensurePublicMembership(club: Club, now: Instant = clock.now()): League {
        leagues.listForClub(club.id).firstOrNull { it.kind == LeagueKind.PUBLIC && it.status == LeagueStatus.ACTIVE }?.let { return it }
        val open = leagues.findOpenPublic(club.tzBucket, club.division, Int.MAX_VALUE)?.let { leagues.lockById(it.id) }?.takeIf { it.currentRound == 0 }
        if (open != null) {
            val members = leagues.members(open.id)   // read under the league lock: two joiners never take the same bot slot
            val bot = clubs.findByIds(members).firstOrNull { it.isBot }
            if (bot != null) {
                leagues.removeMember(open.id, bot.id)
                leagues.addMember(open.id, club.id)
                fixtures.deleteScheduled(open.id, open.seasonNumber)
                prepareSeason(open)
                return open
            }
        }
        val league = leagues.create(
            League(UUID.randomUUID(), LeagueKind.PUBLIC, com.arenacity.domain.rules.Divisions.name(club.division), null, club.division, club.tzBucket, club.timezone, LeagueRules.MATCHDAY_HOUR, 1, LeagueRules.firstSeasonStart(club.timezone, now), 0, null, now, now),
        )
        leagues.addMember(league.id, club.id)
        prepareSeason(league)
        return league
    }

    /** Fills the league with bots up to 8 and generates the season's fixtures (idempotent). */
    suspend fun prepareSeason(league: League): List<Fixture> {
        val existing = fixtures.listByLeagueSeason(league.id, league.seasonNumber)
        if (existing.isNotEmpty()) return existing
        var members = leagues.members(league.id)
        val offset = Math.floorMod(league.id.hashCode(), 24)
        var i = 0
        val names = clubs.findByIds(members).map { it.name }.toMutableSet()
        while (members.size < LeagueRules.PUBLIC_SIZE) {
            val bot = bots.acquire(league.tzBucket, league.division, league.timezone, (offset + i++).mod(24), names)   // distinct names within a league
            leagues.addMember(league.id, bot.id); members = members + bot.id; names += bot.name
        }
        val seed = league.id.leastSignificantBits xor league.seasonNumber.toLong() xor members.size.toLong()
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

/** The lineup that plays for a club in one section: stored (with injured starters auto-replaced) or default. */
class LineupService(private val athletes: AthleteRepository, private val lineups: LineupRepository) {
    suspend fun lineup(club: Club, sport: Sport, now: Instant, squad: List<Athlete>? = null): Lineup {
        val list = squad ?: athletes.listByClub(club.id)
        return LineupRules.effective(club.id, sport, lineups.find(club.id, sport), list, now)
    }

    suspend fun engineLineup(club: Club, now: Instant, sport: Sport = Sport.FOOTBALL, squad: List<Athlete>? = null): Pair<com.arenacity.engine.model.Lineup, List<Athlete>> {
        val list = squad ?: athletes.listByClub(club.id)
        val lineup = lineup(club, sport, now, list)
        return EngineBridge.toEngineLineup(club.id.toString(), lineup, list, now).copy(tactics = lineup.tactics) to list
    }
}
