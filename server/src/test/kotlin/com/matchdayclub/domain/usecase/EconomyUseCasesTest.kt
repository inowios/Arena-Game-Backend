package com.matchdayclub.domain.usecase

import com.matchdayclub.domain.exception.ConflictException
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.rules.*
import com.matchdayclub.domain.usecase.athlete.StarterSquadInitializer
import com.matchdayclub.domain.usecase.club.*
import com.matchdayclub.domain.usecase.economy.*
import com.matchdayclub.domain.usecase.league.*
import com.matchdayclub.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class EconomyUseCasesTest {
    private val clock = FixedClock(Instant.parse("2026-10-09T10:00:00Z"))
    private val users = FakeUserRepository(); private val clubs = FakeClubRepository(); private val buildings = FakeBuildingRepository(); private val athletes = FakeAthleteRepository()
    private val leagues = FakeLeagueRepository(); private val fixtures = FakeFixtureRepository(); private val repo = FakeEconomyRepository()
    private val bots = BotService(users, clubs, athletes, clock)
    private val leagueService = LeagueService(leagues, fixtures, clubs, bots, clock)
    private val clubService = ClubService(clubs, buildings, clock)
    private val economy = EconomyService(repo, leagues, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock), JoinPublicLeagueInitializer(clubs, leagueService, clock)))
    private val daily = ClaimDailyRewardUseCase(clubService, clubs, economy, clock)
    private val pass = SeasonPassUseCases(clubService, clubs, repo, economy, clock)
    private val collect = CollectIncomeUseCase(clubService, clubs, buildings, clock, economy)
    private val purchase = VerifyPurchaseUseCase(clubService, clubs, repo, pass, mapOf(Store.DEV to DevReceiptVerifier()), clock)
    private val ads = AdRewardUseCase(clubService, clubs, buildings, economy, clock)
    private val user = UUID.randomUUID()

    @Test
    fun `daily reward once per day with streak and reset`() = runBlocking {
        create(user, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val a = daily(user); assertEquals(1, a.day); assertEquals(1, a.streak); assertEquals(2_650, a.club.coins)
        assertFailsWith<ConflictException> { daily(user) }
        clock.advanceSeconds(86_400); val b = daily(user); assertEquals(2, b.day); assertEquals(2, b.streak)
        clock.advanceSeconds(2 * 86_400); val c = daily(user); assertEquals(1, c.day); assertEquals(1, c.streak)
    }

    @Test
    fun `tasks progress through collect and pass levels are claimable`() = runBlocking {
        create(user, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val view = pass.view(user)
        assertEquals(3, view.tasks.size); assertEquals(0, view.level)
        val office = buildings.rows.values.first { it.type == BuildingType.TICKET_OFFICE }
        repeat(3) { clock.advanceSeconds(3600); collect(user, office.id) }
        val collectTask = pass.view(user).tasks.firstOrNull { it.task == TaskKind.COLLECT_3.name }
        if (collectTask != null) {
            assertEquals(3, collectTask.progress)
            val after = pass.claimTask(user, TaskKind.COLLECT_3)
            assertEquals(40, after.pass.xp); assertFailsWith<ConflictException> { pass.claimTask(user, TaskKind.COLLECT_3) }
        }
        repo.upsertPass(repo.passes.values.first().copy(xp = 520))
        val (reward, v2) = pass.claimLevels(user)
        assertEquals(5, v2.pass.claimedFree); assertTrue(reward.coins >= 100 + 200 + 300 + 400); assertTrue(reward.gold >= 3)
        assertFailsWith<ConflictException> { pass.claimLevels(user) }
    }

    @Test
    fun `purchases grant once and are idempotent`() = runBlocking {
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
    fun `double income ad doubles the next collect and has a cooldown`() = runBlocking {
        create(user, "Lions", "#AA0000", "#FFFFFF", "UTC")
        ads(user, AdPlacement.DOUBLE_INCOME, null)
        assertFailsWith<ConflictException> { ads(user, AdPlacement.DOUBLE_INCOME, null) }
        val office = buildings.rows.values.first { it.type == BuildingType.TICKET_OFFICE }
        clock.advanceSeconds(3600)
        assertEquals(240, collect(user, office.id).coins)
        clock.advanceSeconds(3600)
        assertEquals(120, collect(user, office.id).coins)
    }
}
