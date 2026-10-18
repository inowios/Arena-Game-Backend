package com.arenacity.domain.usecase.league

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.model.*
import com.arenacity.domain.repository.*
import com.arenacity.domain.rules.ClubLevels
import com.arenacity.domain.rules.LeagueRules
import com.arenacity.domain.rules.ProgressEvent
import com.arenacity.domain.rules.Schedule
import com.arenacity.domain.rules.Sections
import com.arenacity.domain.rules.Standings
import com.arenacity.domain.usecase.athlete.SectionService
import com.arenacity.domain.usecase.economy.ProgressTracker
import com.arenacity.engine.model.EventType
import com.arenacity.engine.model.Tempo
import com.arenacity.engine.model.Plan
import com.arenacity.engine.sim.MatchSimulator
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
    private val failures: com.arenacity.common.ops.FailureSink = com.arenacity.common.ops.NoFailures,
    /** The clubs' heroes: a human club's heroes play its matches (rules.HeroSquad); null = athletes only (tests). */
    private val heroes: com.arenacity.domain.repository.HeroRepository? = null,
) {
    /** A club's match squad: its heroes as athletes (when it has any) with its athletes as the academy pupils. */
    private suspend fun squadOf(club: Club, now: Instant): Pair<List<Athlete>, Set<UUID>> {
        val pupils = athletes.listByClub(club.id)
        val h = heroes ?: return pupils to emptySet()
        if (club.isBot) return pupils to emptySet()
        val playing = com.arenacity.domain.rules.HeroSquad.playing(h.listByClub(club.id), h.lineup(club.id))
        if (playing.isEmpty()) return pupils to emptySet()
        val heroAthletes = com.arenacity.domain.rules.HeroSquad.athletes(club.id, playing, now)
        return (heroAthletes + pupils) to heroAthletes.map { it.id }.toSet()
    }

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
                failures.report("league", e)
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
        // all clubs of the round locked in one global order first, so a parallel two-club transaction can't deadlock with the round
        round.flatMap { listOf(it.homeClubId, it.awayClubId) }.distinct().sorted().forEach { clubs.lockById(it) }
        val played = round.mapNotNull { playFixture(it, now) }
        leagues.update(l.copy(currentRound = next, updatedAt = now))
        return Step(true, played)
    }

    private suspend fun startNewSeason(l: League, now: Instant): League {
        val today = now.atZone(ZoneId.of(l.timezone)).toLocalDate()
        return leagues.update(l.copy(seasonNumber = l.seasonNumber + 1, seasonStart = today.plusDays(1), currentRound = 0, updatedAt = now))
    }

    private data class SectionPlay(val sport: Sport, val section: SectionResult, val homeStarters: Set<String>, val awayStarters: Set<String>, val homeTempo: Double, val awayTempo: Double)

    private fun tempoCost(t: com.arenacity.engine.model.Tactics) = (if (t.tempo == Tempo.HIGH) 1.5 else 1.0) * (if (t.plan == Plan.PRESSING) 1.2 else 1.0)

    /** Simulates every section of one fixture and stores it with effects. Returns null if it was already played. */
    suspend fun playFixture(fixture: Fixture, now: Instant): PlayedFixture? {
        val home = clubs.findById(fixture.homeClubId) ?: return null
        val away = clubs.findById(fixture.awayClubId) ?: return null
        val (homeSquad, homeHeroes) = squadOf(home, now)
        val (awaySquad, awayHeroes) = squadOf(away, now)
        val hSec = sections?.active(home, homeSquad) ?: listOf(Sport.FOOTBALL)
        val aSec = sections?.active(away, awaySquad) ?: listOf(Sport.FOOTBALL)
        // friendlies and challenges play the sections both clubs have; league fixtures play every section either club fields
        val sports = if (fixture.kind != FixtureKind.LEAGUE) Sections.ORDER.filter { it in hSec && it in aSec }.ifEmpty { listOf(Sport.FOOTBALL) }
                     else Sections.ORDER.filter { it in hSec || it in aSec }
        val plays = sports.map { sport ->
            val profile = Sections.profile(sport)
            val bothPlay = fixture.kind != FixtureKind.LEAGUE || (sport in hSec && sport in aSec)
            if (bothPlay) {
                val (hl, _) = lineupService.engineLineup(home, now, sport, homeSquad, stored = homeHeroes.isEmpty())
                val (al, _) = lineupService.engineLineup(away, now, sport, awaySquad, stored = awayHeroes.isEmpty())
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
        var result = MatchdayResult(2, plays.map { it.section })
        if (fixture.kind == FixtureKind.CUP && result.homePoints == result.awayPoints) {
            // a level cup tie is settled from the spot: the starters' technique and nerve decide the kick odds
            fun nerve(squad: List<Athlete>, ids: Set<String>) = squad.filter { it.id.toString() in ids }.map { (it.technique + it.mental) / 2.0 }.average().takeUnless { it.isNaN() } ?: 50.0
            val main = plays.firstOrNull { it.sport == Sport.FOOTBALL } ?: plays.first()
            val h = nerve(homeSquad, main.homeStarters); val a = nerve(awaySquad, main.awayStarters)
            val (hs, aws) = com.arenacity.domain.rules.Cups.shootout(com.arenacity.domain.rules.Cups.kickChance(h, a), com.arenacity.domain.rules.Cups.kickChance(a, h), fixture.seed xor 0x5EED5L)
            result = result.copy(shootout = Shootout(hs, aws))
        }
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
                    applyEffects(fresh.getValue(home.id), homeSquad, plays, true, fixture.kind, now, heroIds = homeHeroes)
                    applyEffects(fresh.getValue(away.id), awaySquad, plays, false, fixture.kind, now, heroIds = awayHeroes)
                    if (!home.isBot) recordProgress(home.id, plays, true, result.homePoints > result.awayPoints)
                    if (!away.isBot) recordProgress(away.id, plays, false, result.awayPoints > result.homePoints)
                } else if (fixture.kind == FixtureKind.CUP) {
                    // cup ties tire both squads like a friendly; prizes are paid by the cup for the round reached
                    val fresh = listOf(home.id, away.id).sorted().associateWith { clubs.lockById(it) ?: error("club $it vanished") }
                    if (!home.isBot) applyEffects(fresh.getValue(home.id), homeSquad, plays, true, fixture.kind, now, fixedCoins = 0, heroIds = homeHeroes)
                    if (!away.isBot) applyEffects(fresh.getValue(away.id), awaySquad, plays, false, fixture.kind, now, fixedCoins = 0, heroIds = awayHeroes)
                    val homeWon = com.arenacity.domain.rules.Cups.winner(played, result) == home.id
                    if (!home.isBot) { progress.record(home.id, ProgressEvent.MATCH_PLAYED); if (homeWon) progress.record(home.id, ProgressEvent.MATCH_WON) }
                    if (!away.isBot) { progress.record(away.id, ProgressEvent.MATCH_PLAYED); if (!homeWon) progress.record(away.id, ProgressEvent.MATCH_WON) }
                } else if (fixture.kind == FixtureKind.CHALLENGE) {
                    // both clubs agreed to a challenge: both get the friendly reward and fatigue
                    val fresh = listOf(home.id, away.id).sorted().associateWith { clubs.lockById(it) ?: error("club $it vanished") }
                    applyEffects(fresh.getValue(home.id), homeSquad, plays, true, fixture.kind, now, heroIds = homeHeroes)
                    applyEffects(fresh.getValue(away.id), awaySquad, plays, false, fixture.kind, now, heroIds = awayHeroes)
                    if (!home.isBot) progress.record(home.id, ProgressEvent.FRIENDLY_PLAYED)
                    if (!away.isBot) progress.record(away.id, ProgressEvent.FRIENDLY_PLAYED)
                } else {
                    // a friendly only affects the club that asked for it; the opponent's squad is borrowed, not tired
                    applyEffects(clubs.lockById(home.id) ?: home, homeSquad, plays, true, fixture.kind, now, heroIds = homeHeroes)
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

    /** [heroIds]: the club's heroes in [squad] (rules.HeroSquad): they play, but they are not athletes rows to update. */
    private suspend fun applyEffects(club: Club, squad: List<Athlete>, plays: List<SectionPlay>, isHome: Boolean, kind: FixtureKind, now: Instant, fixedCoins: Long? = null, heroIds: Set<UUID> = emptySet()) {
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
                    updates[a.id] = m.copy(morale = com.arenacity.domain.rules.Careers.moraleAfter(m.morale, id in starters, mine), updatedAt = now)
                }
                if (id !in starters && id !in injured) continue
                var n = updates[a.id] ?: a
                n = n.copy(fatigue = (n.currentFatigue(now) + fatigue).coerceAtMost(100), fatigueAt = now, updatedAt = now)
                if (id in injured) n = n.copy(injuredUntil = now.plusSeconds(Standings.INJURY_DAYS * 86_400))
                updates[a.id] = n
            }
        }
        if (kind != FixtureKind.LEAGUE) coins = fixedCoins ?: Standings.FRIENDLY_COINS
        val newRep = club.reputation + rep
        clubs.update(club.copy(coins = club.coins + coins, reputation = newRep, level = maxOf(club.level, ClubLevels.levelFor(newRep)), updatedAt = now))
        updates.values.filter { it.id !in heroIds }.forEach { athletes.update(it) }   // the heroes are not athletes rows
    }
}
