package com.matchdayclub.domain.usecase

import com.matchdayclub.domain.exception.ConflictException
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.LiveStatus
import com.matchdayclub.domain.rules.Arenas
import com.matchdayclub.domain.rules.Outcome
import com.matchdayclub.domain.usecase.athlete.StarterSquadInitializer
import com.matchdayclub.domain.usecase.club.ClubService
import com.matchdayclub.domain.usecase.club.CreateClubUseCase
import com.matchdayclub.domain.usecase.league.BotService
import com.matchdayclub.domain.usecase.league.LineupService
import com.matchdayclub.domain.usecase.live.FinishLiveMatchUseCase
import com.matchdayclub.domain.usecase.live.LiveReport
import com.matchdayclub.domain.usecase.live.StartLiveMatchUseCase
import com.matchdayclub.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class LiveMatchTest {
    private val clock = FixedClock(Instant.parse("2026-10-09T10:00:00Z"))
    private val users = FakeUserRepository(); private val clubs = FakeClubRepository(); private val buildings = FakeBuildingRepository()
    private val athletes = FakeAthleteRepository(); private val lineups = FakeLineupRepository(); private val live = FakeLiveMatchRepository(clubs)
    private val bots = BotService(users, clubs, athletes, clock)
    private val clubService = ClubService(clubs, buildings, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock)))
    private val finish = FinishLiveMatchUseCase(clubService, clubs, athletes, live, clock)
    private val start = StartLiveMatchUseCase(clubService, clubs, athletes, LineupService(athletes, lineups), bots, live, finish, clock)
    private val u1 = UUID.randomUUID()

    private fun win(duration: Double = 250.0) = LiveReport(2, 1, duration = duration)

    @Test
    fun `a won match pays coins, trophies, reputation and tires the starters`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val s = start(u1, null)
        assertEquals(11, s.home.starters.size); assertEquals(11, s.away.starters.size); assertTrue(s.away.club.isBot)
        assertEquals(LiveStatus.OPEN, live.rows.getValue(s.match.id).status)
        val r = finish(u1, s.match.id, win())
        assertEquals(Outcome.WIN, r.outcome)
        assertTrue(r.trophiesDelta in 15..35, "delta ${r.trophiesDelta}")
        assertEquals(r.trophiesDelta, r.club.trophies)
        assertEquals(club.coins + Arenas.coins(Outcome.WIN, Arenas.of(0)), r.club.coins)
        assertEquals(club.reputation + Arenas.reputation(Outcome.WIN), r.club.reputation)
        assertEquals(1, r.club.liveStreak)
        val tired = athletes.listByClub(club.id).filter { it.id in s.match.starters }
        assertTrue(tired.all { it.currentFatigue(clock.now()) == Arenas.MATCH_FATIGUE }, "fatigue ${tired.map { it.fatigue }}")
        assertEquals(LiveStatus.FINISHED, live.rows.getValue(s.match.id).status)
        assertFailsWith<ConflictException> { finish(u1, s.match.id, win()) }
    }

    @Test
    fun `a loss costs trophies but never below zero and a made-up report is refused`() = runBlocking<Unit> {
        create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val s = start(u1, null)
        assertFailsWith<ValidationException> { finish(u1, s.match.id, LiveReport(1, 0, duration = 20.0)) }
        assertFailsWith<ValidationException> { finish(u1, s.match.id, LiveReport(12, 0, duration = 300.0)) }
        val r = finish(u1, s.match.id, LiveReport(0, 2, duration = 300.0))
        assertEquals(Outcome.LOSS, r.outcome); assertEquals(0, r.club.trophies); assertEquals(0, r.club.liveStreak)
    }

    @Test
    fun `three wins in a row open a player pack and an exhausted squad must rest`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val before = athletes.listByClub(club.id).size
        repeat(3) { i ->
            val s = start(u1, null)
            val r = finish(u1, s.match.id, win())
            assertEquals(i + 1, r.club.liveStreak)
            assertEquals(i == 2, r.pack != null, "pack after win ${i + 1}")
        }
        assertEquals(before + 1, athletes.listByClub(club.id).size)
        // three matches = 66 fatigue on the starters; a fourth still works (fresh substitutes are drawn), the fifth cannot field eleven
        start(u1, null).let { finish(u1, it.match.id, win()) }
        assertFailsWith<ConflictException> { start(u1, null) }
        clock.advanceSeconds(60L * 60)   // an hour later everyone is rested
        start(u1, null)
    }

    @Test
    fun `a match left open counts as a defeat when the next one starts`() = runBlocking<Unit> {
        create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val first = start(u1, null)
        clubs.rows.values.first { it.userId == u1 }.let { clubs.rows[it.id] = it.copy(trophies = 100) }
        val second = start(u1, null)
        assertEquals(LiveStatus.ABANDONED, live.rows.getValue(first.match.id).status)
        assertTrue(second.home.club.trophies < 100)
        assertEquals(LiveStatus.OPEN, live.rows.getValue(second.match.id).status)
    }
}

class LiveReadinessTest {
    private val clock = FixedClock(Instant.parse("2026-10-09T10:00:00Z"))
    private val users = FakeUserRepository(); private val clubs = FakeClubRepository(); private val buildings = FakeBuildingRepository()
    private val athletes = FakeAthleteRepository(); private val lineups = FakeLineupRepository(); private val live = FakeLiveMatchRepository(clubs)
    private val bots = BotService(users, clubs, athletes, clock)
    private val clubService = ClubService(clubs, buildings, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock)))
    private val finish = FinishLiveMatchUseCase(clubService, clubs, athletes, live, clock)
    private val start = StartLiveMatchUseCase(clubService, clubs, athletes, LineupService(athletes, lineups), bots, live, finish, clock)
    private val readiness = com.matchdayclub.domain.usecase.live.LiveReadinessUseCase(clubService, athletes, LineupService(athletes, lineups), buildings, clock)
    private val rest = com.matchdayclub.domain.usecase.live.RestSquadUseCase(clubService, clubs, athletes, readiness, clock)
    private val u1 = UUID.randomUUID()

    @Test
    fun `energy drops with matches and gold brings the squad back`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        assertEquals(100, readiness(u1).energy); assertTrue(readiness(u1).ready)
        repeat(4) { val s = start(u1, null); finish(u1, s.match.id, LiveReport(1, 0, duration = 250.0)) }
        val r = readiness(u1)
        assertFalse(r.ready); assertTrue(r.restMinutes > 0); assertTrue(r.energy < 50, "energy ${r.energy}")
        clubs.rows[club.id] = clubs.rows.getValue(club.id).copy(gold = 100)
        val after = rest(u1)
        assertEquals(100 - r.restGold, after.gold)
        assertTrue(readiness(u1).ready); assertEquals(100, readiness(u1).energy)
        assertFailsWith<ConflictException> { rest(u1) }
    }
}
