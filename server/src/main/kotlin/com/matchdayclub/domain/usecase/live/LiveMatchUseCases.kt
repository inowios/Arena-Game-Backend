package com.matchdayclub.domain.usecase.live

import com.matchdayclub.common.time.Clock
import com.matchdayclub.common.tx.NoTx
import com.matchdayclub.common.tx.Tx
import com.matchdayclub.domain.exception.ConflictException
import com.matchdayclub.domain.exception.NotFoundException
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.repository.AthleteRepository
import com.matchdayclub.domain.repository.ClubRepository
import com.matchdayclub.domain.repository.LiveMatchRepository
import com.matchdayclub.domain.rules.*
import com.matchdayclub.domain.rules.BuildingType
import com.matchdayclub.domain.usecase.account.InboxService
import com.matchdayclub.domain.usecase.athlete.LineupRules
import com.matchdayclub.domain.usecase.club.ClubService
import com.matchdayclub.domain.usecase.economy.ProgressTracker
import com.matchdayclub.domain.usecase.league.BotService
import com.matchdayclub.domain.usecase.league.LineupService
import com.matchdayclub.engine.model.Plan
import com.matchdayclub.engine.model.Position
import com.matchdayclub.engine.model.Tactics
import com.matchdayclub.engine.model.Tempo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random

/** One side of a live match as handed to the device. */
data class LiveTeam(val club: Club, val starters: List<Athlete>, val bench: List<Athlete>, val tactics: Tactics, val overall: Int)
/** [seats]: the home stadium's capacity — the crowd the device draws and the gate the result pays. */
data class LiveStart(val match: LiveMatch, val home: LiveTeam, val away: LiveTeam, val halfSeconds: Int = LiveRules.HALF_SECONDS, val seats: Int = 500)

/** What the device reports when the match is over. */
@Serializable data class LiveReportEvent(val minute: Int, val type: String, val side: String, val athleteId: String? = null, val athleteId2: String? = null)
@Serializable data class LiveMinutes(val athleteId: String, val minutes: Int = 90, val goals: Int = 0, val assists: Int = 0)
@Serializable data class LiveReport(
    val homeScore: Int, val awayScore: Int, val homeShots: Int = 0, val awayShots: Int = 0, val homeOnTarget: Int = 0, val awayOnTarget: Int = 0,
    val homePossession: Int = 50, val duration: Double = 0.0, val auto: Boolean = false,
    val events: List<LiveReportEvent> = emptyList(), val minutes: List<LiveMinutes> = emptyList(),
)

data class LiveOutcome(val match: LiveMatch, val club: Club, val outcome: Outcome, val arena: Arena, val coins: Long, val reputation: Int, val trophiesDelta: Int, val pack: Athlete?, val athletes: List<Athlete>, val growth: List<GrowthResult> = emptyList(), val gate: Long = 0)

object LiveRules {
    const val HALF_SECONDS = 120
    /** The shortest a genuinely played match can be (two halves) — anything shorter is a made-up report. */
    const val MIN_DURATION_SECONDS = 180.0
    const val MAX_GOALS = 9
    const val OPPONENT_BAND = 150
    /** How long after the hand-out a match may still be given back for another opponent. */
    const val CANCEL_SECONDS = 120L
    /** The gate: a coin per twenty seats of the home stadium, every match played to the end. */
    fun gate(seats: Int): Long = (seats / 20).toLong()
    fun seats(buildings: List<com.matchdayclub.domain.model.Building>?): Int = buildings?.firstOrNull { it.type == BuildingType.PITCH }?.let { BuildingCatalog.level(BuildingType.PITCH, it.level).effect2 } ?: 500

    fun tempoCost(t: Tactics) = (if (t.tempo == Tempo.HIGH) 1.5 else 1.0) * (if (t.plan == Plan.PRESSING) 1.2 else 1.0)

    fun overall(starters: List<Athlete>) = if (starters.isEmpty()) 0 else starters.map { it.overall }.average().roundToInt()

    /**
     * The eleven that can play now: the saved line-up with injured and exhausted starters swapped for rested players of
     * the same position (any outfield position as a last resort). Null when the squad cannot field a fit eleven.
     */
    fun restedEleven(lineup: Lineup, squad: List<Athlete>, now: Instant): Pair<List<Athlete>, List<Athlete>>? {
        val byId = squad.associateBy { it.id }
        fun fit(a: Athlete) = !a.isInjured(now) && a.currentFatigue(now) < Arenas.MAX_FATIGUE_TO_PLAY && a.retiredAt == null
        val starters = lineup.starters.mapNotNull { byId[it] }.toMutableList()
        val others = squad.filter { it !in starters && fit(it) }.sortedByDescending { it.overall }.toMutableList()
        for (i in starters.indices) {
            val s = starters[i]
            if (fit(s)) continue
            val sub = others.firstOrNull { it.position == s.position } ?: others.firstOrNull { it.position != Position.GK && s.position != Position.GK } ?: return null
            others.remove(sub); starters[i] = sub
        }
        if (starters.size < LineupRules.STARTERS || starters.count { it.position == Position.GK } != 1) return null
        val bench = (lineup.bench.mapNotNull { byId[it] }.filter(::fit) + others).distinct().filter { it !in starters }.take(LineupRules.MAX_BENCH)
        return starters to bench
    }

    /** Minutes until the most tired fit-to-play athlete is rested enough. */
    fun minutesToRest(squad: List<Athlete>, now: Instant): Int {
        val worst = squad.filter { !it.isInjured(now) && it.retiredAt == null }.maxOfOrNull { it.currentFatigue(now) } ?: 0
        return ((worst - Arenas.MAX_FATIGUE_TO_PLAY + 1).coerceAtLeast(1) * FATIGUE_RECOVERY_SECONDS / 60).toInt()
    }
}

/** Hands out a live match: the user's rested eleven against a club near his trophies (a real club's saved line-up, or a bot of the arena). */
class StartLiveMatchUseCase(
    private val service: ClubService, private val clubs: ClubRepository, private val athletes: AthleteRepository, private val lineupService: LineupService,
    private val bots: BotService, private val live: LiveMatchRepository, private val finisher: FinishLiveMatchUseCase, private val clock: Clock, private val tx: Tx = NoTx,
    private val buildings: com.matchdayclub.domain.repository.BuildingRepository? = null,
) {
    suspend operator fun invoke(userId: UUID, opponentClubId: UUID?): LiveStart = tx {
        var club = service.lockedClubOf(userId)
        val now = clock.now()
        // a match left open (the app was closed mid-game) is a defeat
        for (open in live.findOpen(club.id)) club = finisher.abandon(club, open, now).club
        val squad = athletes.listByClub(club.id).filter { it.sport == Sport.FOOTBALL && it.retiredAt == null }
        val lineup = lineupService.lineup(club, Sport.FOOTBALL, now, squad)
        val (starters, bench) = LiveRules.restedEleven(lineup, squad, now) ?: throw ConflictException("Your players are exhausted — rest {0} min", LiveRules.minutesToRest(squad, now))
        val opponent = pickOpponent(club, opponentClubId, now)
        val oppSquad = athletes.listByClub(opponent.id).filter { it.sport == Sport.FOOTBALL && it.retiredAt == null }
        val oppLineup = lineupService.lineup(opponent, Sport.FOOTBALL, now, oppSquad)
        val oppById = oppSquad.associateBy { it.id }
        val oppStarters = oppLineup.starters.mapNotNull { oppById[it] }
        val oppBench = oppLineup.bench.mapNotNull { oppById[it] }
        val myOverall = LiveRules.overall(starters); val oppOverall = LiveRules.overall(oppStarters)
        val match = live.create(LiveMatch(UUID.randomUUID(), club.id, opponent.id, now.toEpochMilli() xor club.id.leastSignificantBits xor opponent.id.mostSignificantBits, LiveStatus.OPEN,
            starters.map { it.id }, oppStarters.map { it.id }, LiveRules.tempoCost(lineup.tactics), myOverall, oppOverall, now))
        LiveStart(match, LiveTeam(club, starters, bench, lineup.tactics, myOverall), LiveTeam(opponent, oppStarters, oppBench, oppLineup.tactics, oppOverall), seats = LiveRules.seats(buildings?.listByClub(club.id)))
    }

    private suspend fun pickOpponent(club: Club, wanted: UUID?, now: Instant): Club {
        if (wanted != null) {
            if (wanted == club.id) throw ValidationException("Cannot play against yourself")
            return clubs.findById(wanted) ?: throw NotFoundException("Opponent not found")
        }
        val rnd = Random(now.toEpochMilli() xor club.id.leastSignificantBits)
        val arena = Arenas.of(club.trophies)
        val humans = live.opponents(club.id, club.trophies - LiveRules.OPPONENT_BAND, club.trophies + LiveRules.OPPONENT_BAND, 20)
        if (humans.isNotEmpty() && rnd.nextInt(100) < 50) return humans[rnd.nextInt(humans.size)]
        val pool = clubs.listBots(club.tzBucket).filter { it.division == arena.botDivision }
        return if (pool.isNotEmpty()) pool[rnd.nextInt(pool.size)] else bots.createBot(club.tzBucket, arena.botDivision, club.timezone, rnd.nextInt(24))
    }
}

/** The club's last live matches with the opponents' cards (the profile's "recent matches"). */
class RecentLiveMatchesUseCase(private val service: ClubService, private val clubs: ClubRepository, private val live: LiveMatchRepository) {
    data class Row(val match: LiveMatch, val opponent: Club?)
    suspend operator fun invoke(userId: UUID, limit: Int = 10): List<Row> {
        val club = service.clubOf(userId)
        val rows = live.recent(club.id, limit)
        val opps = clubs.findByIds(rows.map { it.opponentId }.toSet()).associateBy { it.id }
        return rows.map { Row(it, opps[it.opponentId]) }
    }
}

/** "Another opponent": a match handed out but not kicked off yet (within [LiveRules.CANCEL_SECONDS]) is given back without a result. */
class CancelLiveMatchUseCase(private val service: ClubService, private val live: LiveMatchRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, matchId: UUID): LiveMatch = tx {
        val club = service.lockedClubOf(userId)
        val m = live.find(matchId)?.takeIf { it.clubId == club.id } ?: throw NotFoundException("Match not found")
        if (m.status != LiveStatus.OPEN) throw ConflictException("Match already finished")
        val now = clock.now()
        if (now.epochSecond - m.createdAt.epochSecond > LiveRules.CANCEL_SECONDS) throw ConflictException("Too late to look for another opponent")
        live.update(m.copy(status = LiveStatus.CANCELLED))
    }
}

/** Takes the device's report, checks it is plausible and applies the match: fatigue, trophies, coins, reputation, packs, the defender's side. */
class FinishLiveMatchUseCase(
    private val service: ClubService, private val clubs: ClubRepository, private val athletes: AthleteRepository, private val live: LiveMatchRepository,
    private val clock: Clock, private val progress: ProgressTracker = ProgressTracker { _, _ -> }, private val inbox: InboxService? = null, private val tx: Tx = NoTx,
    private val buildings: com.matchdayclub.domain.repository.BuildingRepository? = null,
) {
    private val json = Json { encodeDefaults = true }

    suspend operator fun invoke(userId: UUID, matchId: UUID, report: LiveReport): LiveOutcome = tx {
        val club = service.lockedClubOf(userId)
        val now = clock.now()
        val m = live.find(matchId)?.takeIf { it.clubId == club.id } ?: throw NotFoundException("Match not found")
        if (m.status != LiveStatus.OPEN) throw ConflictException("Match already finished")
        val forfeit = report.homeScore == 0 && report.awayScore == 3 && report.duration < LiveRules.MIN_DURATION_SECONDS
        if (report.homeScore !in 0..LiveRules.MAX_GOALS || report.awayScore !in 0..LiveRules.MAX_GOALS) throw ValidationException("Implausible match report")
        if (!forfeit && report.duration < LiveRules.MIN_DURATION_SECONDS) throw ValidationException("Implausible match report")
        if (report.events.size > 80) throw ValidationException("Implausible match report")
        apply(club, m, report, now, pack = !forfeit)
    }

    /** An open match the player walked away from: a 0:3 defeat with the usual costs. */
    suspend fun abandon(club: Club, m: LiveMatch, now: Instant): LiveOutcome = apply(club, m, LiveReport(0, 3), now, pack = false, status = LiveStatus.ABANDONED)

    private suspend fun apply(club: Club, m: LiveMatch, report: LiveReport, now: Instant, pack: Boolean, status: LiveStatus = LiveStatus.FINISHED): LiveOutcome {
        val outcome = Standings.outcome(report.homeScore, report.awayScore)
        val arena = Arenas.of(club.trophies)
        val newTrophies = (club.trophies + Arenas.delta(outcome, m.myOverall, m.oppOverall)).coerceAtLeast(0)
        val delta = newTrophies - club.trophies
        val myBuildings = buildings?.listByClub(club.id)
        val gate = if (status == LiveStatus.FINISHED) LiveRules.gate(LiveRules.seats(myBuildings)) else 0L
        val coins = Arenas.coins(outcome, arena) + gate
        val rep = Arenas.reputation(outcome)
        val streak = if (outcome == Outcome.WIN) club.liveStreak + 1 else 0
        // the starters pay in fatigue and feel the result
        val fatigue = (Arenas.MATCH_FATIGUE * m.tempo).roundToInt()
        val points = when (outcome) { Outcome.WIN -> 3; Outcome.DRAW -> 1; Outcome.LOSS -> 0 }
        val squad = athletes.listByClub(club.id)
        val starterIds = m.starters.toSet()
        // experience: minutes, goals, assists, saves and the result; the venue caps attributes like training does
        val cap = TrainingRules.attributeCap(Sport.FOOTBALL, myBuildings?.firstOrNull { it.type == BuildingType.PITCH }?.level ?: 1)
        val byAthlete = report.minutes.associateBy { it.athleteId }
        val saves = report.events.count { it.type == "SAVE" && it.side == "HOME" }
        val rnd = Random(m.seed xor now.toEpochMilli())
        val growth = mutableListOf<GrowthResult>()
        val updated = squad.filter { it.id in starterIds }.map {
            val tired = it.copy(fatigue = (it.currentFatigue(now) + fatigue).coerceAtMost(100), fatigueAt = now, morale = Careers.moraleAfter(it.morale, true, points), updatedAt = now)
            if (status != LiveStatus.FINISHED) return@map tired
            val mine = byAthlete[it.id.toString()]
            val xp = Growth.xpFor(it, mine?.minutes ?: 90, mine?.goals ?: 0, mine?.assists ?: 0, if (it.position == Position.GK) saves else 0, outcome)
            val g = Growth.apply(tired, xp, cap, rnd); growth += g; g.athlete
        }
        updated.forEach { athletes.update(it) }
        // a player pack every third win in a row (while the squad has room)
        var packAthlete: Athlete? = null
        if (pack && outcome == Outcome.WIN && streak % Arenas.PACK_EVERY_WINS == 0 && squad.count { it.sport == Sport.FOOTBALL && it.retiredAt == null } < Careers.squadMax(Sport.FOOTBALL)) {
            val q = Arenas.packQuality(arena)
            packAthlete = AthleteFactory.create(m.seed xor now.toEpochMilli(), club.id, Sport.FOOTBALL, q.first, q.last, null, now)
            athletes.createAll(listOf(packAthlete))
        }
        val newRep = club.reputation + rep
        val saved = clubs.update(club.copy(coins = club.coins + coins, reputation = newRep, level = maxOf(club.level, ClubLevels.levelFor(newRep)), trophies = newTrophies, liveStreak = streak, updatedAt = now))
        val finished = live.update(m.copy(status = status, homeScore = report.homeScore, awayScore = report.awayScore, resultJson = json.encodeToString(report), trophiesDelta = delta, finishedAt = now))
        progress.record(club.id, ProgressEvent.MATCH_PLAYED)
        if (outcome == Outcome.WIN) progress.record(club.id, ProgressEvent.MATCH_WON)
        // the club whose line-up defended: a smaller swing the other way, and a note in its inbox
        val opp = clubs.findById(m.opponentId)
        if (opp != null && !opp.isBot) {
            val dd = Arenas.defenderDelta(delta)
            clubs.update(opp.copy(trophies = (opp.trophies + dd).coerceAtLeast(0), updatedAt = now))
            inbox?.add(opp.id, "LIVE_DEFENCE", mapOf("club" to club.name, "home" to report.homeScore.toString(), "away" to report.awayScore.toString(), "trophies" to dd.toString()))
        }
        val fresh = athletes.listByClub(club.id)
        return LiveOutcome(finished, saved, outcome, Arenas.of(newTrophies), coins, rep, delta, packAthlete, fresh, growth, gate)
    }
}

/** How ready the squad is for a live match: energy of the eleven, whether they can start, minutes until they can. */
data class LiveReadiness(val energy: Int, val ready: Boolean, val restMinutes: Int, val tired: Int, val restGold: Int)

class LiveReadinessUseCase(private val service: ClubService, private val athletes: AthleteRepository, private val lineupService: LineupService, private val buildings: com.matchdayclub.domain.repository.BuildingRepository, private val clock: Clock) {
    suspend operator fun invoke(userId: UUID): LiveReadiness = of(service.clubOf(userId))

    suspend fun of(club: Club): LiveReadiness {
        val now = clock.now()
        val squad = athletes.listByClub(club.id).filter { it.sport == Sport.FOOTBALL && it.retiredAt == null }
        val lineup = lineupService.lineup(club, Sport.FOOTBALL, now, squad)
        val byId = squad.associateBy { it.id }
        val eleven = lineup.starters.mapNotNull { byId[it] }
        val energy = if (eleven.isEmpty()) 100 else eleven.map { 100 - it.currentFatigue(now) }.average().roundToInt()
        val tired = eleven.count { it.currentFatigue(now) >= Arenas.MAX_FATIGUE_TO_PLAY || it.isInjured(now) }
        val ready = LiveRules.restedEleven(lineup, squad, now) != null
        val medical = buildings.listByClub(club.id).firstOrNull { it.type == BuildingType.MEDICAL_CENTER }?.level ?: 0
        val worstMinutes = (eleven.maxOfOrNull { it.currentFatigue(now) } ?: 0) * FATIGUE_RECOVERY_SECONDS / 60
        return LiveReadiness(energy, ready, if (ready) 0 else LiveRules.minutesToRest(squad, now), tired, LiveRules.restGold(worstMinutes.toInt(), medical))
    }
}

/** Gold for a full recovery of the eleven: a gold per five minutes the most tired man still needs, cheaper with a medical centre, never free. */
fun LiveRules.restGold(minutesToFull: Int, medicalLevel: Int): Int = (Math.ceil(minutesToFull / 5.0) * (1.0 - 0.15 * medicalLevel)).roundToInt().coerceAtLeast(2)

/** Pays gold to rest the whole squad at once (the "army" is back): every athlete's fatigue goes to zero. */
class RestSquadUseCase(private val service: ClubService, private val clubs: ClubRepository, private val athletes: AthleteRepository, private val readiness: LiveReadinessUseCase, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID): Club = tx {
        val club = service.lockedClubOf(userId)
        val now = clock.now()
        val r = readiness.of(club)
        val squad = athletes.listByClub(club.id).filter { it.sport == Sport.FOOTBALL && it.retiredAt == null }
        if (squad.all { it.currentFatigue(now) == 0 }) throw ConflictException("Everyone is already rested")
        val charged = service.charge(club, gold = r.restGold)
        squad.filter { it.currentFatigue(now) > 0 }.forEach { athletes.update(it.copy(fatigue = 0, fatigueAt = now, updatedAt = now)) }
        charged
    }
}

/** The weekly arena chest: once an ISO week (club time), coins and gold by the arena the club stands in. */
data class ArenaChest(val arena: Arena, val coins: Long, val gold: Int, val available: Boolean, val nextAt: Instant)

class ArenaChestUseCase(private val service: ClubService, private val clubs: ClubRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    fun view(club: Club, now: Instant): ArenaChest {
        val zone = java.time.ZoneId.of(club.timezone); val arena = Arenas.of(club.trophies)
        return ArenaChest(arena, Arenas.chestCoins(arena), Arenas.chestGold(arena), club.arenaChestWeek != Arenas.isoWeek(now, zone), Arenas.nextWeek(now, zone))
    }
    suspend fun view(userId: UUID): ArenaChest = view(service.clubOf(userId), clock.now())
    suspend fun claim(userId: UUID): Pair<ArenaChest, Club> = tx {
        val club = service.lockedClubOf(userId); val now = clock.now()
        val chest = view(club, now)
        if (!chest.available) throw ConflictException("The arena chest opens next week")
        val saved = clubs.update(club.copy(coins = club.coins + chest.coins, gold = club.gold + chest.gold, arenaChestWeek = Arenas.isoWeek(now, java.time.ZoneId.of(club.timezone)), updatedAt = now))
        chest.copy(available = false) to saved
    }
}
