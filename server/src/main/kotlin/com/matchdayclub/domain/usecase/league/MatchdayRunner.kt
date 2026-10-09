package com.matchdayclub.domain.usecase.league

import com.matchdayclub.common.time.Clock
import com.matchdayclub.common.tx.NoTx
import com.matchdayclub.common.tx.Tx
import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.repository.*
import com.matchdayclub.domain.rules.ClubLevels
import com.matchdayclub.domain.rules.LeagueRules
import com.matchdayclub.domain.rules.ProgressEvent
import com.matchdayclub.domain.rules.Schedule
import com.matchdayclub.domain.rules.Sections
import com.matchdayclub.domain.rules.Standings
import com.matchdayclub.domain.usecase.athlete.SectionService
import com.matchdayclub.domain.usecase.economy.ProgressTracker
import com.matchdayclub.engine.model.EventType
import com.matchdayclub.engine.model.Tempo
import com.matchdayclub.engine.model.Plan
import com.matchdayclub.engine.sim.MatchSimulator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt

/** A stored fixture with its result; [home]/[away] are the clubs as they were before the effects. */
data class PlayedFixture(val fixture: Fixture, val result: MatchdayResult, val home: Club, val away: Club)

/** Result of closing a season: the updated league plus side effects (pushes) to run after the transaction commits. */
data class SeasonClose(val league: League, val after: List<suspend () -> Unit> = emptyList())

/** Hook for season end (divisions, prizes). Default: just start the next season in place. */
fun interface SeasonCloser { suspend fun close(league: League, now: Instant): SeasonClose }

/**
 * Plays due league rounds. Single-instance by design (in-process mutex); each round runs in one transaction with the
 * league row locked, fixture updates are conditional on SCHEDULED, so a duplicate run is a no-op. A matchday is one
 * opponent across all sections both clubs field; a section only one club fields is a walkover for that club.
 */
class MatchdayRunner(
    private val leagues: LeagueRepository,
    private val fixtures: FixtureRepository,
    private val clubs: ClubRepository,
    private val athletes: AthleteRepository,
    private val lineupService: LineupService,
    private val leagueService: LeagueService,
    private val clock: Clock,
    private val progress: ProgressTracker = ProgressTracker { _, _ -> },
    private val notifier: MatchdayNotifier? = null,
    private val tx: Tx = NoTx,
    private val sections: SectionService? = null,
    private val seasonCloser: SeasonCloser? = null,
) {
    private val log = LoggerFactory.getLogger("MatchdayRunner")
    private val mutex = Mutex()

    private data class Step(val advanced: Boolean, val played: List<PlayedFixture> = emptyList(), val after: List<suspend () -> Unit> = emptyList())

    /** @return number of matches played in this tick. */
    suspend fun runDueRounds(now: Instant = clock.now()): Int = mutex.withLock {
        var played = 0
        for (league in leagues.listAll(LeagueKind.PUBLIC)) {
            try {
                played += runLeague(league.id, now)
            } catch (e: Exception) {
                log.error("league ${league.id} failed", e)
            }
        }
        played
    }

    private suspend fun runLeague(leagueId: UUID, now: Instant): Int {
        var played = 0
        var guard = 0
        while (guard++ < LeagueRules.ROUNDS * 2 + 4) {
            val step = tx { step(leagueId, now) }
            played += step.played.size
            // pushes go out only after the round is committed
            step.played.forEach { p -> notifier?.let { n -> runCatching { n.notify(p.fixture, p.home, p.away, p.result) }.onFailure { e -> log.warn("notify failed", e) } } }
            step.after.forEach { runCatching { it() }.onFailure { e -> log.warn("season notice failed", e) } }
            if (!step.advanced) break
        }
        return played
    }

    private suspend fun step(leagueId: UUID, now: Instant): Step {
        val l = leagues.lockById(leagueId) ?: return Step(false)
        if (l.kind != LeagueKind.PUBLIC || l.status == LeagueStatus.FINISHED) return Step(false)
        val next = l.currentRound + 1
        if (next > LeagueRules.ROUNDS) {
            if (!Schedule.seasonOver(l, now)) return Step(false)
            val closed = seasonCloser?.close(l, now) ?: SeasonClose(startNewSeason(l, now))
            val c = closed.league
            return Step(c.status != LeagueStatus.FINISHED && c.currentRound == 0 && c.seasonNumber != l.seasonNumber, after = closed.after)
        }
        if (Schedule.roundInstant(l.seasonStart, next, l.timezone, l.matchdayHour).isAfter(now)) return Step(false)
        val all = leagueService.prepareSeason(l)
        val round = all.filter { it.round == next && it.status == FixtureStatus.SCHEDULED }
        val played = round.mapNotNull { playFixture(it, now) }
        leagues.update(l.copy(currentRound = next, updatedAt = now))
        return Step(true, played)
    }

    private suspend fun startNewSeason(l: League, now: Instant): League {
        val today = now.atZone(ZoneId.of(l.timezone)).toLocalDate()
        return leagues.update(l.copy(seasonNumber = l.seasonNumber + 1, seasonStart = today.plusDays(1), currentRound = 0, updatedAt = now))
    }

    private data class SectionPlay(val sport: Sport, val section: SectionResult, val homeStarters: Set<String>, val awayStarters: Set<String>, val homeTempo: Double, val awayTempo: Double)

    private fun tempoCost(t: com.matchdayclub.engine.model.Tactics) = (if (t.tempo == Tempo.HIGH) 1.5 else 1.0) * (if (t.plan == Plan.PRESSING) 1.2 else 1.0)

    /** Simulates every section of one fixture and stores it with effects. Returns null if it was already played. */
    suspend fun playFixture(fixture: Fixture, now: Instant): PlayedFixture? {
        val home = clubs.findById(fixture.homeClubId) ?: return null
        val away = clubs.findById(fixture.awayClubId) ?: return null
        val homeSquad = athletes.listByClub(home.id)
        val awaySquad = athletes.listByClub(away.id)
        val hSec = sections?.active(home, homeSquad) ?: listOf(Sport.FOOTBALL)
        val aSec = sections?.active(away, awaySquad) ?: listOf(Sport.FOOTBALL)
        // friendlies and challenges play the sections both clubs have; league fixtures play every section either club fields
        val sports = if (fixture.kind != FixtureKind.LEAGUE) Sections.ORDER.filter { it in hSec && it in aSec }.ifEmpty { listOf(Sport.FOOTBALL) }
                     else Sections.ORDER.filter { it in hSec || it in aSec }
        val plays = sports.map { sport ->
            val profile = Sections.profile(sport)
            val bothPlay = fixture.kind != FixtureKind.LEAGUE || (sport in hSec && sport in aSec)
            if (bothPlay) {
                val (hl, _) = lineupService.engineLineup(home, now, sport, homeSquad)
                val (al, _) = lineupService.engineLineup(away, now, sport, awaySquad)
                val seed = fixture.seed xor (sport.ordinal.toLong() * -7046029254386353131L)
                val r = MatchSimulator.simulate(hl, al, profile, seed)
                SectionPlay(sport, SectionResult(sport.name, r.score.home, r.score.away, Sections.points(r.score.home, r.score.away), Sections.points(r.score.away, r.score.home), null, r),
                    hl.starters.map { it.id }.toSet(), al.starters.map { it.id }.toSet(), tempoCost(hl.tactics), tempoCost(al.tactics))
            } else {
                val homeTurnsUp = sport in hSec
                val w = profile.walkover
                SectionPlay(sport, SectionResult(sport.name, if (homeTurnsUp) w.home else w.away, if (homeTurnsUp) w.away else w.home, if (homeTurnsUp) 3 else 0, if (homeTurnsUp) 0 else 3, if (homeTurnsUp) "HOME" else "AWAY", null),
                    emptySet(), emptySet(), 1.0, 1.0)
            }
        }
        val result = MatchdayResult(2, plays.map { it.section })
        val football = result.section(Sport.FOOTBALL) ?: result.sections.firstOrNull()
        val played = fixture.copy(
            status = FixtureStatus.PLAYED, homeScore = football?.homeScore ?: 0, awayScore = football?.awayScore ?: 0,
            homePoints = result.homePoints, awayPoints = result.awayPoints, resultJson = result.toJson(), playedAt = now,
        )
        val stored = tx {
            if (!fixtures.markPlayed(played)) false else {
                if (fixture.kind == FixtureKind.LEAGUE) {
                    // stable lock order (by id) so two fixtures touching the same clubs never deadlock
                    val fresh = listOf(home.id, away.id).sorted().associateWith { clubs.lockById(it) ?: error("club $it vanished") }
                    applyEffects(fresh.getValue(home.id), homeSquad, plays, true, fixture.kind, now)
                    applyEffects(fresh.getValue(away.id), awaySquad, plays, false, fixture.kind, now)
                    if (!home.isBot) recordProgress(home.id, plays, true, result.homePoints > result.awayPoints)
                    if (!away.isBot) recordProgress(away.id, plays, false, result.awayPoints > result.homePoints)
                } else if (fixture.kind == FixtureKind.CHALLENGE) {
                    // both clubs agreed to a challenge: both get the friendly reward and fatigue
                    val fresh = listOf(home.id, away.id).sorted().associateWith { clubs.lockById(it) ?: error("club $it vanished") }
                    applyEffects(fresh.getValue(home.id), homeSquad, plays, true, fixture.kind, now)
                    applyEffects(fresh.getValue(away.id), awaySquad, plays, false, fixture.kind, now)
                    if (!home.isBot) progress.record(home.id, ProgressEvent.FRIENDLY_PLAYED)
                    if (!away.isBot) progress.record(away.id, ProgressEvent.FRIENDLY_PLAYED)
                } else {
                    // a friendly only affects the club that asked for it; the opponent's squad is borrowed, not tired
                    applyEffects(clubs.lockById(home.id) ?: home, homeSquad, plays, true, fixture.kind, now)
                    if (!home.isBot) progress.record(home.id, ProgressEvent.FRIENDLY_PLAYED)
                }
                true
            }
        }
        return if (stored) PlayedFixture(played, result, home, away) else null
    }

    private suspend fun recordProgress(clubId: UUID, plays: List<SectionPlay>, isHome: Boolean, won: Boolean) {
        if (won) progress.record(clubId, ProgressEvent.MATCH_WON)
        for (p in plays) if ((if (isHome) p.section.homePoints else p.section.awayPoints) == 3) progress.record(clubId, ProgressEvent.SECTION_WON)
        progress.record(clubId, ProgressEvent.MATCH_PLAYED)
    }

    private suspend fun applyEffects(club: Club, squad: List<Athlete>, plays: List<SectionPlay>, isHome: Boolean, kind: FixtureKind, now: Instant) {
        val teamId = club.id.toString()
        var coins = 0L; var rep = 0
        val updates = mutableMapOf<UUID, Athlete>()
        for (p in plays) {
            val mine = if (isHome) p.section.homePoints else p.section.awayPoints
            val walkoverLoss = p.section.walkover != null && mine == 0
            val outcome = Standings.outcomeFromPoints(mine)
            if (kind == FixtureKind.LEAGUE && !walkoverLoss) { coins += Standings.coinsReward(outcome, club.division); rep += Standings.reputationDelta(outcome) }
            val result = p.section.result ?: continue
            val starters = if (isHome) p.homeStarters else p.awayStarters
            val tempo = if (isHome) p.homeTempo else p.awayTempo
            val base = if (kind == FixtureKind.LEAGUE) Standings.MATCH_FATIGUE else Standings.FRIENDLY_FATIGUE
            val fatigue = (base * tempo).roundToInt()
            val injured = result.events.filter { it.type == EventType.INJURY && it.team == teamId }.mapNotNull { it.athleteId }.toSet()
            for (a in squad) {
                val id = a.id.toString()
                // morale: playing time and results (league matchdays, human clubs)
                if (kind == FixtureKind.LEAGUE && !club.isBot && a.sport.name == p.section.sport) {
                    val m = updates[a.id] ?: a
                    updates[a.id] = m.copy(morale = com.matchdayclub.domain.rules.Careers.moraleAfter(m.morale, id in starters, mine), updatedAt = now)
                }
                if (id !in starters && id !in injured) continue
                var n = updates[a.id] ?: a
                n = n.copy(fatigue = (n.currentFatigue(now) + fatigue).coerceAtMost(100), fatigueAt = now, updatedAt = now)
                if (id in injured) n = n.copy(injuredUntil = now.plusSeconds(Standings.INJURY_DAYS * 86_400))
                updates[a.id] = n
            }
        }
        if (kind != FixtureKind.LEAGUE) coins = Standings.FRIENDLY_COINS
        val newRep = club.reputation + rep
        clubs.update(club.copy(coins = club.coins + coins, reputation = newRep, level = maxOf(club.level, ClubLevels.levelFor(newRep)), updatedAt = now))
        updates.values.forEach { athletes.update(it) }
    }
}
