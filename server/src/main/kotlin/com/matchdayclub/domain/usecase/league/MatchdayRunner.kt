package com.matchdayclub.domain.usecase.league

import com.matchdayclub.common.time.Clock
import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.repository.*
import com.matchdayclub.domain.rules.ClubLevels
import com.matchdayclub.domain.rules.LeagueRules
import com.matchdayclub.domain.rules.Schedule
import com.matchdayclub.domain.rules.Standings
import com.matchdayclub.engine.model.EventType
import com.matchdayclub.engine.model.FOOTBALL
import com.matchdayclub.engine.model.MatchResult
import com.matchdayclub.engine.sim.MatchSimulator
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Plays due league rounds. Single-instance by design (in-process mutex); fixture updates are
 * conditional on SCHEDULED status so a duplicate run is a no-op.
 */
class MatchdayRunner(
    private val leagues: LeagueRepository,
    private val fixtures: FixtureRepository,
    private val clubs: ClubRepository,
    private val athletes: AthleteRepository,
    private val lineupService: LineupService,
    private val leagueService: LeagueService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger("MatchdayRunner")
    private val mutex = Mutex()
    private val json = Json

    /** @return number of matches played in this tick. */
    suspend fun runDueRounds(now: Instant = clock.now()): Int = mutex.withLock {
        var played = 0
        for (league in leagues.listAll(LeagueKind.PUBLIC)) {
            try {
                played += runLeague(league, now)
            } catch (e: Exception) {
                log.error("league ${league.id} failed", e)
            }
        }
        played
    }

    private suspend fun runLeague(league: League, now: Instant): Int {
        var l = league
        var played = 0
        while (true) {
            val next = l.currentRound + 1
            if (next > LeagueRules.ROUNDS) { l = startNewSeason(l, now); continue }
            if (Schedule.roundInstant(l.seasonStart, next, l.timezone, l.matchdayHour).isAfter(now)) return played
            val all = leagueService.prepareSeason(l)
            val round = all.filter { it.round == next && it.status == FixtureStatus.SCHEDULED }
            for (f in round) { if (playFixture(f, now) != null) played++ }
            l = leagues.update(l.copy(currentRound = next, updatedAt = now))
        }
    }

    private suspend fun startNewSeason(l: League, now: Instant): League {
        val today = now.atZone(ZoneId.of(l.timezone)).toLocalDate()
        return leagues.update(l.copy(seasonNumber = l.seasonNumber + 1, seasonStart = today.plusDays(1), currentRound = 0, updatedAt = now))
    }

    /** Simulates and stores one fixture, applying rewards and fatigue/injuries. Returns null if it was already played. */
    suspend fun playFixture(fixture: Fixture, now: Instant): Pair<Fixture, MatchResult>? {
        val home = clubs.findById(fixture.homeClubId) ?: return null
        val away = clubs.findById(fixture.awayClubId) ?: return null
        val (hl, hs) = lineupService.engineLineup(home, now)
        val (al, `as`) = lineupService.engineLineup(away, now)
        val result = MatchSimulator.simulate(hl, al, FOOTBALL, fixture.seed)
        val played = fixture.copy(status = FixtureStatus.PLAYED, homeScore = result.score.home, awayScore = result.score.away, resultJson = json.encodeToString(result), playedAt = now)
        if (!fixtures.markPlayed(played)) return null
        applyEffects(home, hs, hl.starters.map { it.id }, result.score.home, result.score.away, result, fixture.kind, now)
        applyEffects(away, `as`, al.starters.map { it.id }, result.score.away, result.score.home, result, fixture.kind, now)
        return played to result
    }

    private suspend fun applyEffects(club: Club, squad: List<Athlete>, starterIds: List<String>, gf: Int, ga: Int, result: MatchResult, kind: FixtureKind, now: Instant) {
        val outcome = Standings.outcome(gf, ga)
        val updated = if (kind == FixtureKind.LEAGUE) {
            val rep = club.reputation + Standings.reputationDelta(outcome)
            club.copy(coins = club.coins + Standings.coinsReward(outcome), reputation = rep, level = ClubLevels.levelFor(rep), updatedAt = now)
        } else club.copy(coins = club.coins + Standings.FRIENDLY_COINS, updatedAt = now)
        clubs.update(updated)
        val fatigue = if (kind == FixtureKind.LEAGUE) Standings.MATCH_FATIGUE else Standings.FRIENDLY_FATIGUE
        val injured = result.events.filter { it.type == EventType.INJURY && it.team == club.id.toString() }.mapNotNull { it.athleteId }.toSet()
        for (a in squad) {
            val id = a.id.toString()
            if (id !in starterIds && id !in injured) continue
            var n = a.copy(fatigue = (a.currentFatigue(now) + fatigue).coerceAtMost(100), fatigueAt = now, updatedAt = now)
            if (id in injured) n = n.copy(injuredUntil = now.plusSeconds(Standings.INJURY_DAYS * 86_400))
            athletes.update(n)
        }
    }
}
