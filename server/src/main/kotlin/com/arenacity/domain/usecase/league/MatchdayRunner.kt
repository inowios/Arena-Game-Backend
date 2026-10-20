package com.arenacity.domain.usecase.league

import com.arenacity.arena.HeroPick
import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.model.*
import com.arenacity.domain.repository.*
import com.arenacity.domain.rules.ClubLevels
import com.arenacity.domain.rules.HeroSquad
import com.arenacity.domain.rules.Heroes
import com.arenacity.domain.rules.LeagueRules
import com.arenacity.domain.rules.ProgressEvent
import com.arenacity.domain.rules.Schedule
import com.arenacity.domain.rules.Standings
import com.arenacity.domain.usecase.economy.ProgressTracker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** A stored fixture with its result; [home]/[away] are the clubs as they were before the effects. */
data class PlayedFixture(val fixture: Fixture, val result: MatchdayResult, val home: Club, val away: Club)

/** Result of closing a season: the updated league plus side effects (pushes) to run after the transaction commits. */
data class SeasonClose(val league: League, val after: List<suspend () -> Unit> = emptyList())

/** Hook for season end (divisions, prizes). Default: just start the next season in place. */
fun interface SeasonCloser { suspend fun close(league: League, now: Instant): SeasonClose }

/**
 * Plays due league rounds. Single-instance by design (in-process mutex); each round runs in one transaction with the
 * league row locked, fixture updates are conditional on SCHEDULED, so a duplicate run is a no-op. Every fixture (league,
 * cup, friendly, challenge) is an Arena v4 match between the clubs' hero triples (rules.HeroSquad, HeroMatch): win or
 * loss, 1:0 in the table.
 */
class MatchdayRunner(
    private val leagues: LeagueRepository,
    private val fixtures: FixtureRepository,
    private val clubs: ClubRepository,
    private val leagueService: LeagueService,
    private val clock: Clock,
    private val progress: ProgressTracker = ProgressTracker { _, _ -> },
    private val notifier: MatchdayNotifier? = null,
    private val tx: Tx = NoTx,
    private val seasonCloser: SeasonCloser? = null,
    private val failures: com.arenacity.common.ops.FailureSink = com.arenacity.common.ops.NoFailures,
    /** The clubs' heroes: a human club fields its saved triple and earns experience; null = seeded triples only (tests). */
    private val heroes: HeroRepository? = null,
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

    /** The triple [club] fields in [fixture]: its saved Arena triple filled with its best heroes; a bot or a club without heroes gets a seeded one. */
    suspend fun tripleOf(club: Club, fixture: Fixture): List<HeroPick> {
        val seed = fixture.seed xor club.id.leastSignificantBits
        val h = heroes ?: return HeroSquad.seeded(seed)
        if (club.isBot) return HeroSquad.seeded(seed)
        val own = HeroSquad.triple(h.listByClub(club.id), h.lineup(club.id))
        return if (own.isEmpty()) HeroSquad.seeded(seed) else HeroSquad.fill(own, seed)
    }

    /** Plays one fixture as an Arena match and stores it with its effects. Returns null if it was already played. */
    suspend fun playFixture(fixture: Fixture, now: Instant): PlayedFixture? {
        val home = clubs.findById(fixture.homeClubId) ?: return null
        val away = clubs.findById(fixture.awayClubId) ?: return null
        val homeTriple = tripleOf(home, fixture); val awayTriple = tripleOf(away, fixture)
        val result = HeroMatch.play(homeTriple, awayTriple, fixture.seed)
        val played = fixture.copy(
            status = FixtureStatus.PLAYED, homeScore = result.homeScore, awayScore = result.awayScore,
            homePoints = result.homePoints, awayPoints = result.awayPoints, resultJson = result.toJson(), playedAt = now,
        )
        val stored = tx {
            if (!fixtures.markPlayed(played)) false else {
                // stable lock order (by id) so two fixtures touching the same clubs never deadlock
                val fresh = listOf(home.id, away.id).sorted().associateWith { clubs.lockById(it) ?: error("club $it vanished") }
                val h = fresh.getValue(home.id); val a = fresh.getValue(away.id)
                when (fixture.kind) {
                    FixtureKind.LEAGUE -> {
                        applyEffects(h, homeTriple, result.homeWon, fixture.kind, now)
                        applyEffects(a, awayTriple, !result.homeWon, fixture.kind, now)
                        if (!home.isBot) recordProgress(home.id, result.homeWon)
                        if (!away.isBot) recordProgress(away.id, !result.homeWon)
                    }
                    FixtureKind.CUP -> {
                        // the cup pays by the round reached (CupService); the heroes still earn their experience
                        if (!home.isBot) applyEffects(h, homeTriple, result.homeWon, fixture.kind, now, fixedCoins = 0)
                        if (!away.isBot) applyEffects(a, awayTriple, !result.homeWon, fixture.kind, now, fixedCoins = 0)
                        if (!home.isBot) { progress.record(home.id, ProgressEvent.MATCH_PLAYED); if (result.homeWon) progress.record(home.id, ProgressEvent.MATCH_WON) }
                        if (!away.isBot) { progress.record(away.id, ProgressEvent.MATCH_PLAYED); if (!result.homeWon) progress.record(away.id, ProgressEvent.MATCH_WON) }
                    }
                    FixtureKind.CHALLENGE -> {
                        // both clubs agreed to a challenge: both get the friendly reward
                        applyEffects(h, homeTriple, result.homeWon, fixture.kind, now)
                        applyEffects(a, awayTriple, !result.homeWon, fixture.kind, now)
                        if (!home.isBot) progress.record(home.id, ProgressEvent.FRIENDLY_PLAYED)
                        if (!away.isBot) progress.record(away.id, ProgressEvent.FRIENDLY_PLAYED)
                    }
                    FixtureKind.FRIENDLY -> {
                        // a friendly only affects the club that asked for it; the opponent's triple is borrowed
                        applyEffects(h, homeTriple, result.homeWon, fixture.kind, now)
                        if (!home.isBot) progress.record(home.id, ProgressEvent.FRIENDLY_PLAYED)
                    }
                }
                true
            }
        }
        return if (stored) PlayedFixture(played, result, home, away) else null
    }

    private suspend fun recordProgress(clubId: UUID, won: Boolean) {
        if (won) { progress.record(clubId, ProgressEvent.MATCH_WON); progress.record(clubId, ProgressEvent.SECTION_WON) }
        progress.record(clubId, ProgressEvent.MATCH_PLAYED)
    }

    /** The club's reward for the fixture (league: coins and reputation by the result and the division; else the friendly coins) and its heroes' experience. */
    private suspend fun applyEffects(club: Club, triple: List<HeroPick>, won: Boolean, kind: FixtureKind, now: Instant, fixedCoins: Long? = null) {
        val outcome = if (won) com.arenacity.domain.rules.Outcome.WIN else com.arenacity.domain.rules.Outcome.LOSS
        val coins = if (kind == FixtureKind.LEAGUE) Standings.coinsReward(outcome, club.division) else fixedCoins ?: Standings.FRIENDLY_COINS
        val rep = if (kind == FixtureKind.LEAGUE) Standings.reputationDelta(outcome) else 0
        val newRep = club.reputation + rep
        clubs.update(club.copy(coins = club.coins + coins, reputation = newRep, level = maxOf(club.level, ClubLevels.levelFor(newRep)), updatedAt = now))
        grantXp(club, triple, won, now)
    }

    /** The heroes who played earn as in an Arena match (rules.Heroes.matchXp, the daily cap); bots and seeded triples earn nothing. */
    private suspend fun grantXp(club: Club, triple: List<HeroPick>, won: Boolean, now: Instant) {
        val h = heroes ?: return
        if (club.isBot) return
        val ids = triple.map { it.id }.toSet()
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        val xp = Heroes.matchXp(won, draw = false)
        for (hero in h.listByClub(club.id)) if (hero.heroId in ids) h.update(Heroes.grant(hero, xp, today))
    }
}
