package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.domain.usecase.economy.*
import com.arenacity.domain.usecase.league.*
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class EconomyUseCasesTest {
    private val clock = FixedClock(Instant.parse("2026-10-09T10:00:00Z"))
    private val users = FakeUserRepository(); private val clubs = FakeClubRepository()
    private val leagues = FakeLeagueRepository(); private val fixtures = FakeFixtureRepository(); private val repo = FakeEconomyRepository()
    private val bots = BotService(users, clubs, clock)
    private val leagueService = LeagueService(leagues, fixtures, clubs, bots, clock)
    private val clubService = ClubService(clubs, clock)
    private val economy = EconomyService(repo, leagues, clock)
    private val create = CreateClubUseCase(clubs, clock, listOf(JoinPublicLeagueInitializer(clubs, leagueService, clock)))
    private val daily = ClaimDailyRewardUseCase(clubService, clubs, economy, clock)
    private val pass = SeasonPassUseCases(clubService, clubs, repo, economy, clock)
    private val purchase = VerifyPurchaseUseCase(clubService, clubs, repo, pass, mapOf(Store.DEV to DevReceiptVerifier()), clock)
    private val user = UUID.randomUUID()

    @Test
    fun `daily reward once per day with streak and reset`() = runBlocking<Unit> {
        create(user, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val a = daily(user); assertEquals(1, a.day); assertEquals(1, a.streak); assertEquals(2_650, a.club.coins)
        assertFailsWith<ConflictException> { daily(user) }
        clock.advanceSeconds(86_400); val b = daily(user); assertEquals(2, b.day); assertEquals(2, b.streak)
        clock.advanceSeconds(2 * 86_400); val c = daily(user); assertEquals(1, c.day); assertEquals(1, c.streak)
    }

    @Test
    fun `tasks progress through events and pass levels are claimable`() = runBlocking<Unit> {
        val club = create(user, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val view = pass.view(user)
        assertEquals(3, view.tasks.size); assertEquals(0, view.level)
        repeat(2) { economy.record(club.id, ProgressEvent.TRAINING_STARTED) }
        val trainTask = pass.view(user).tasks.firstOrNull { it.task == TaskKind.TRAIN_1.name }
        if (trainTask != null) {
            assertEquals(1, trainTask.progress)   // one hero training a day, capped at the target
            val after = pass.claimTask(user, TaskKind.TRAIN_1)
            assertEquals(40, after.pass.xp); assertFailsWith<ConflictException> { pass.claimTask(user, TaskKind.TRAIN_1) }
        }
        repo.upsertPass(repo.passes.values.first().copy(xp = 520))
        val claimed = pass.claimLevels(user); val reward = claimed.reward; val v2 = claimed.view
        assertEquals(5, v2.pass.claimedFree); assertTrue(reward.coins >= 100 + 200 + 300 + 400); assertTrue(reward.gold >= 3)
        assertFailsWith<ConflictException> { pass.claimLevels(user) }
        Unit   // JUnit only runs test methods that return void
    }

    @Test
    fun `purchases grant once and are idempotent`() = runBlocking<Unit> {
        create(user, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val r = purchase(user, Store.DEV, "gold_medium", "dev:order-1")
        assertEquals("GRANTED", r.status); assertEquals(600, r.club.gold)
        val dup = purchase(user, Store.DEV, "gold_medium", "dev:order-1")
        assertEquals("DUPLICATE", dup.status); assertEquals(600, clubs.rows.values.first().gold)
        assertFailsWith<ValidationException> { purchase(user, Store.DEV, "gold_medium", "bogus") }
        purchase(user, Store.DEV, "season_pass", "dev:order-2")
        assertTrue(pass.view(user).pass.premium)
    }

    @Test
    fun `a task that went with the city builder is left out of a day stored before`() = runBlocking<Unit> {
        val club = create(user, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val today = economy.today("UTC")
        repo.upsertTask(com.arenacity.domain.model.DailyTask(club.id, today, "COLLECT_3", 1, false))
        repo.upsertTask(com.arenacity.domain.model.DailyTask(club.id, today, "PLAY_LIVE_3", 0, false))   // the old live match's task name
        repo.upsertTask(com.arenacity.domain.model.DailyTask(club.id, today, TaskKind.TRAIN_1.name, 0, false))
        assertEquals(listOf(TaskKind.TRAIN_1.name), pass.view(user).tasks.map { it.task })
    }
}
