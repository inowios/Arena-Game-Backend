package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.ArenaMatchStatus
import com.arenacity.domain.rules.Arenas
import com.arenacity.domain.rules.Economy
import com.arenacity.domain.rules.Metric
import com.arenacity.domain.rules.ProgressEvent
import com.arenacity.domain.rules.TaskKind
import com.arenacity.domain.usecase.arena.ArenaMatchUseCases
import com.arenacity.domain.usecase.arena.ArenaReport
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.club.CreateClubUseCase
import com.arenacity.domain.usecase.economy.EconomyService
import com.arenacity.domain.usecase.hero.HeroUseCases
import com.arenacity.domain.usecase.hero.StartHeroesInitializer
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ArenaMatchTest {
    private val clock = FixedClock(Instant.parse("2026-10-20T10:00:00Z"))
    private val clubs = FakeClubRepository(); private val heroes = FakeHeroRepository(); private val matches = FakeArenaMatchRepository()
    private val economyRepo = FakeEconomyRepository(); private val achievements = FakeAchievementRepository()
    private val service = ClubService(clubs, clock)
    private val economy = EconomyService(economyRepo, FakeLeagueRepository(), clock, achievements = achievements)
    private val heroUse = HeroUseCases(service, heroes, clock, progress = economy)
    private val create = CreateClubUseCase(clubs, clock, listOf(StartHeroesInitializer(heroUse)))
    private val arena = ArenaMatchUseCases(service, clubs, matches, clock, heroes = heroUse, progress = economy)
    private val u1 = UUID.randomUUID()

    private fun club() = clubs.rows.values.first { it.userId == u1 }
    /** A start with the energy full again first (energy is not the point of these tests). */
    private suspend fun start(stake: Int = 1) = clubs.update(club().copy(energy = 30, energyAt = null)).let { arena.start(u1, stake) }

    @Test
    fun `a start hands out a token for energy, the result needs it and is taken once`() = runBlocking<Unit> {
        create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val s = arena.start(u1)
        assertEquals(25, s.energy.current); assertEquals(1, s.match.stake); assertNull(s.match.story); assertEquals(ArenaMatchStatus.OPEN, s.match.status)
        assertFailsWith<ValidationException> { arena.start(u1, stake = 3) }
        assertFailsWith<NotFoundException> { arena.report(u1, ArenaReport(UUID.randomUUID(), won = true)) }
        assertEquals("No draws in the Arena", assertFailsWith<ValidationException> { arena.report(u1, ArenaReport(s.match.id, won = false, draw = true)) }.template)
        assertFailsWith<ValidationException> { arena.report(u1, ArenaReport(s.match.id, won = true, knockouts = 4)) }
        val o = arena.report(u1, ArenaReport(s.match.id, won = true, heroes = listOf("p001", "p003", "p011"), knockouts = 2, altSkills = 1))
        assertEquals(120, o.coins); assertTrue(o.rewarded); assertEquals(1, o.rewardedToday)
        assertEquals(Arenas.TROPHIES_WIN, o.trophiesDelta); assertEquals(25, club().trophies); assertEquals(1, club().liveStreak)
        assertEquals(ArenaMatchStatus.REPORTED, matches.rows.getValue(s.match.id).status); assertEquals(true, matches.rows.getValue(s.match.id).won)
        assertEquals(50, o.heroXp); assertEquals(listOf("p001", "p003", "p011"), o.heroes.map { it.spec.id })
        assertEquals("Match already reported", assertFailsWith<ConflictException> { arena.report(u1, ArenaReport(s.match.id, won = true)) }.template)
        // another club's token is nobody's
        val u2 = UUID.randomUUID(); create(u2, "Bears", "#AA0000", "#FFFFFF", "UTC")
        assertFailsWith<NotFoundException> { arena.report(u2, ArenaReport(s.match.id, won = true)) }
        // the events: played, won, knockouts, the alternate skill
        assertEquals(2L, achievements.counters[club().id to Metric.KNOCKOUTS]); assertEquals(1L, achievements.counters[club().id to Metric.ALT_SKILLS]); assertEquals(1L, achievements.counters[club().id to Metric.MATCHES_WON])
        val tasks = economyRepo.tasks.values.filter { it.clubId == club().id }
        assertTrue(tasks.any { it.task in setOf(TaskKind.PLAY_ARENA_3.name, TaskKind.WIN_ARENA_2.name, TaskKind.KO_5.name, TaskKind.ALT_SKILL_1.name) && it.progress >= 1 }, tasks.toString())
    }

    @Test
    fun `the stake comes from the start, a loss and a concession move trophies down to zero, the streak breaks`() = runBlocking<Unit> {
        create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val s4 = start(stake = 4)
        assertEquals(480, arena.report(u1, ArenaReport(s4.match.id, won = true)).coins)
        val lost = arena.report(u1, ArenaReport(start(stake = 4).match.id, won = false))
        assertEquals(5, lost.coins); assertEquals(-Arenas.TROPHIES_LOSS, lost.trophiesDelta); assertEquals(7, club().trophies); assertEquals(0, club().liveStreak)
        val conceded = arena.report(u1, ArenaReport(start(stake = 2).match.id, won = true, conceded = true))   // a concession is never a win
        assertEquals(20, conceded.coins); assertEquals(0, club().trophies); assertEquals(0, conceded.heroXp); assertEquals(false, matches.rows.getValue(conceded.match.id).won)
    }

    @Test
    fun `only ten matches a day pay coins, trophies and experience go on`() = runBlocking<Unit> {
        create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        repeat(Economy.ARENA_REWARDED_PER_DAY) { assertTrue(arena.report(u1, ArenaReport(start().match.id, won = true, heroes = listOf("p001"))).rewarded) }
        val coins = club().coins
        val extra = arena.report(u1, ArenaReport(start().match.id, won = true, heroes = listOf("p001")))
        assertFalse(extra.rewarded); assertEquals(0, extra.coins); assertEquals(Economy.ARENA_REWARDED_PER_DAY, extra.rewardedToday); assertEquals(coins, club().coins)
        assertEquals(Arenas.TROPHIES_WIN * 11, club().trophies); assertEquals(50, extra.heroXp)
        clock.advanceSeconds(24 * 3600)   // the next UTC day pays again
        assertTrue(arena.report(u1, ArenaReport(start().match.id, won = true)).rewarded)
        assertEquals(1, club().arenaRewarded)
    }

    @Test
    fun `a new start abandons the match left open, a hero training and the triple move their tasks`() = runBlocking<Unit> {
        create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val first = start().match
        val second = start().match
        assertEquals(ArenaMatchStatus.ABANDONED, matches.rows.getValue(first.id).status)
        assertFailsWith<ConflictException> { arena.report(u1, ArenaReport(first.id, won = true)) }
        arena.report(u1, ArenaReport(second.id, won = false))
        clubs.update(club().copy(coins = 10_000))
        heroUse.train(u1, "p001"); heroUse.saveLineup(u1, listOf("p001", "p003", "p007"))
        assertEquals(1L, achievements.counters[club().id to Metric.TRAININGS])
        val tasks = economyRepo.tasks.values.filter { it.clubId == club().id }.associate { it.task to it.progress }
        tasks[TaskKind.TRAIN_1.name]?.let { assertEquals(1, it) }
        tasks[TaskKind.SET_LINEUP.name]?.let { assertEquals(1, it) }
        assertTrue(ProgressEvent.TRAINING_STARTED.task == TaskKind.TRAIN_1 && ProgressEvent.LINEUP_SAVED.task == TaskKind.SET_LINEUP)
    }
}
