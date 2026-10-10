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
        assertEquals(club.coins + Arenas.coins(Outcome.WIN, Arenas.of(0)) + 25, r.club.coins)   // + the gate of a 500-seat stadium
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
    fun `recent matches list the finished ones with the opponent, newest first`() = runBlocking<Unit> {
        create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val recent = com.matchdayclub.domain.usecase.live.RecentLiveMatchesUseCase(clubService, clubs, live)
        val a = start(u1, null); finish(u1, a.match.id, win()); clock.advanceSeconds(60)
        val b = start(u1, null); finish(u1, b.match.id, LiveReport(0, 1, duration = 250.0))
        val rows = recent(u1)
        assertEquals(2, rows.size); assertEquals(b.match.id, rows[0].match.id); assertEquals(0, rows[0].match.homeScore); assertEquals(1, rows[0].match.awayScore)
        assertNotNull(rows[0].opponent); assertTrue(rows[0].match.trophiesDelta!! < 0); assertTrue(rows[1].match.trophiesDelta!! > 0)
    }

    @Test
    fun `an athlete's record counts his starts, goals and assists`() = runBlocking<Unit> {
        create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val history = com.matchdayclub.domain.usecase.live.AthleteMatchHistoryUseCase(clubService, athletes, clubs, live)
        val s = start(u1, null); val fwd = s.home.starters.first { it.position == com.matchdayclub.engine.model.Position.FWD }
        finish(u1, s.match.id, LiveReport(2, 0, duration = 250.0, minutes = listOf(com.matchdayclub.domain.usecase.live.LiveMinutes(fwd.id.toString(), 90, 2, 0))))
        clock.advanceSeconds(60)
        val s2 = start(u1, null)
        finish(u1, s2.match.id, LiveReport(1, 1, duration = 250.0, minutes = listOf(com.matchdayclub.domain.usecase.live.LiveMinutes(fwd.id.toString(), 90, 0, 1))))
        val h = history(u1, fwd.id)
        assertEquals(2, h.matches); assertEquals(1, h.wins); assertEquals(2, h.goals); assertEquals(1, h.assists)
        assertEquals(s2.match.id, h.rows[0].match.id); assertEquals(1, h.rows[0].assists); assertNotNull(h.rows[0].opponent)
    }

    @Test
    fun `a match given back before kick-off costs nothing`() = runBlocking<Unit> {
        create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val cancel = com.matchdayclub.domain.usecase.live.CancelLiveMatchUseCase(clubService, live, clock)
        val first = start(u1, null)
        clubs.rows.values.first { it.userId == u1 }.let { clubs.rows[it.id] = it.copy(trophies = 100) }
        cancel(u1, first.match.id)
        assertEquals(LiveStatus.CANCELLED, live.rows.getValue(first.match.id).status)
        assertFailsWith<ConflictException> { cancel(u1, first.match.id) }
        val second = start(u1, null)
        assertEquals(100, second.home.club.trophies)
        clock.advanceSeconds(200)
        assertFailsWith<ConflictException> { cancel(u1, second.match.id) }
        assertFailsWith<ConflictException> { finish(u1, first.match.id, win()) }
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

class GrowthTest {
    private val clock = FixedClock(Instant.parse("2026-10-09T10:00:00Z"))
    private val users = FakeUserRepository(); private val clubs = FakeClubRepository(); private val buildings = FakeBuildingRepository()
    private val athletes = FakeAthleteRepository(); private val lineups = FakeLineupRepository(); private val live = FakeLiveMatchRepository(clubs)
    private val bots = BotService(users, clubs, athletes, clock)
    private val clubService = ClubService(clubs, buildings, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock)))
    private val finish = FinishLiveMatchUseCase(clubService, clubs, athletes, live, clock, buildings = buildings)
    private val start = StartLiveMatchUseCase(clubService, clubs, athletes, LineupService(athletes, lineups), bots, live, finish, clock)
    private val u1 = UUID.randomUUID()

    @Test
    fun `starters earn experience and a scorer grows`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val s = start(u1, null)
        val scorer = s.home.starters.first { it.position == com.matchdayclub.engine.model.Position.FWD }
        val before = scorer.overall
        // a hat-trick and a win by a youngster: enough for a point or two
        val r = finish(u1, s.match.id, LiveReport(3, 0, duration = 260.0, minutes = listOf(com.matchdayclub.domain.usecase.live.LiveMinutes(scorer.id.toString(), 90, 3, 0))))
        assertEquals(11, r.growth.size)
        // the gate of the level-1 stadium (500 seats → 25 coins) comes on top of the arena's reward
        assertEquals(25L, r.gate); assertEquals(club.coins + Arenas.coins(Outcome.WIN, Arenas.of(0)) + 25, r.club.coins)
        val g = r.growth.first { it.athlete.id == scorer.id }
        assertTrue(g.xpGained >= 60, "xp ${g.xpGained}")
        val after = athletes.rows.getValue(scorer.id)
        assertTrue(after.xp + g.points * com.matchdayclub.domain.rules.Growth.XP_PER_POINT >= g.xpGained)
        assertTrue(after.overall >= before)
        // experience never pushes past potential
        val capped = athletes.rows.values.first { it.clubId == club.id }.copy(xp = 950, potential = 1)
        val res = com.matchdayclub.domain.rules.Growth.apply(capped, 100, 99, kotlin.random.Random(1))
        assertEquals(0, res.points); assertEquals(99, res.athlete.xp)
    }
}

class ArenaChestTest {
    private val clock = FixedClock(Instant.parse("2026-10-09T10:00:00Z"))   // a Friday
    private val clubs = FakeClubRepository(); private val buildings = FakeBuildingRepository(); private val athletes = FakeAthleteRepository()
    private val clubService = ClubService(clubs, buildings, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock)))
    private val chest = com.matchdayclub.domain.usecase.live.ArenaChestUseCase(clubService, clubs, clock)
    private val u1 = UUID.randomUUID()

    @Test
    fun `the chest opens once a week and scales with the arena`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val v = chest.view(u1); assertTrue(v.available); assertEquals(400L, v.coins); assertEquals(3, v.gold)
        val (c, after) = chest.claim(u1)
        assertFalse(c.available); assertEquals(club.coins + 400, after.coins); assertEquals(club.gold + 3, after.gold)
        assertFailsWith<ConflictException> { chest.claim(u1) }
        clock.advanceSeconds(3 * 86_400)   // Monday
        clubs.rows[club.id] = clubs.rows.getValue(club.id).copy(trophies = 800)   // CITY
        val w = chest.view(u1); assertTrue(w.available); assertEquals("CITY", w.arena.key); assertEquals((400 * 1.6).toLong(), w.coins); assertEquals(7, w.gold)
    }
}
