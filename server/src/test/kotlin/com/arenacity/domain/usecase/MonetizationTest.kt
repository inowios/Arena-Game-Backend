package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.InsufficientFundsException
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.*
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.athlete.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.domain.usecase.economy.*
import com.arenacity.domain.usecase.league.*
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class MonetizationTest {
    private val clock = FixedClock(Instant.parse("2026-10-09T10:00:00Z"))
    private val users = FakeUserRepository(); private val clubs = FakeClubRepository()
    private val athletes = FakeAthleteRepository()
    private val leagues = FakeLeagueRepository(); private val fixtures = FakeFixtureRepository(); private val economyRepo = FakeEconomyRepository()
    private val inventory = FakeInventoryRepository(); private val achievementsRepo = FakeAchievementRepository(); private val seasons = FakeSeasonRepository()
    private val spots = FakeClubSpotRepository()
    private val service = ClubService(clubs, clock, spots)
    private val economy = EconomyService(economyRepo, leagues, clock, achievements = achievementsRepo)
    private val cosmetics = CosmeticsService(inventory, clock)
    private val bots = BotService(users, clubs, athletes, clock)
    private val leagueService = LeagueService(leagues, fixtures, clubs, bots, clock)
    private val create = CreateClubUseCase(clubs, clock, listOf(StarterSquadInitializer(athletes, clock), JoinPublicLeagueInitializer(clubs, leagueService, clock)))
    private val coinPacks = BuyCoinPackUseCase(service, clubs, clock)
    private val achievementService = AchievementService(achievementsRepo, service, seasons)
    private val achievements = AchievementUseCases(service, clubs, achievementsRepo, achievementService, cosmetics, clock)
    private val pass = SeasonPassUseCases(service, clubs, economyRepo, economy, clock, cosmetics = cosmetics, athletes = athletes)
    private val daily = ClaimDailyRewardUseCase(service, clubs, economy, clock)
    private val user = UUID.randomUUID()

    private fun club() = clubs.rows.values.first { it.userId == user }

    /** A club with the map's [open] spots open (its stadium level 1 + their number). */
    private fun setup(gold: Int = 1_000, level: Int = 3, vararg open: String) = runBlocking {
        create(user, "Lions", "#AA0000", "#FFFFFF", "UTC")
        clubs.update(club().copy(coins = 50_000, gold = gold, level = level)); spots.open(club().id, *open)
    }

    @Test
    fun `coin packs turn gold into coins`() = runBlocking<Unit> {
        setup(gold = 120)
        val before = club().coins
        val after = coinPacks(user, "coins_medium")
        assertEquals(20, after.gold); assertEquals(before + 13_000, after.coins)
        assertFailsWith<InsufficientFundsException> { coinPacks(user, "coins_medium") }
        assertFailsWith<NotFoundException> { coinPacks(user, "coins_mega") }
        Unit
    }

    @Test
    fun `achievements pay gold per reached tier from counters and club state`() = runBlocking<Unit> {
        setup(gold = 0, level = 3)
        repeat(5) { economy.record(club().id, ProgressEvent.TRAINING_STARTED) }
        val list = achievements.list(user)
        val coach = list.first { it.achievement.id == "coach" }
        assertEquals(5, coach.value); assertEquals(1, coach.reachedTier); assertTrue(coach.claimable)
        val growing = list.first { it.achievement.id == "growing_club" }
        assertEquals(1, growing.reachedTier)                                           // club level 3
        assertTrue(list.none { it.achievement.id in setOf("tycoon", "clearer", "master_builder") })   // gone with the city builder
        val claim = achievements.claim(user, "coach")
        assertEquals(5, claim.gold); assertEquals(5, club().gold)
        assertFailsWith<ConflictException> { achievements.claim(user, "coach") }
        // the stadium achievement counts the open spots: stadium 1, then 2 with the academy open
        assertFailsWith<ConflictException> { achievements.claim(user, "stadium") }
        spots.open(club().id, MapSpots.ACADEMY)
        assertEquals(2, achievements.list(user).first { it.achievement.id == "stadium" }.value)
        assertEquals(10, achievements.claim(user, "stadium").gold)
        assertFailsWith<ConflictException> { achievements.claim(user, "champion") }
        assertEquals(10, achievements.claim(user, "growing_club").gold)
        Unit
    }

    @Test
    fun `title achievement grants its frame, a duplicate pays gold`() = runBlocking<Unit> {
        setup(gold = 0)
        seasons.createTrophy(Trophy(UUID.randomUUID(), club().id, TrophyKind.LEAGUE_TITLE, 1, 1, clock.now()))
        val c = achievements.claim(user, "champion")
        assertEquals(listOf("frame_laurel"), c.items); assertEquals(50, c.gold)
        assertTrue("frame_laurel" in cosmetics.owned(club().id))
        // promoted:1 gives kit_retro; owning it already turns into gold
        inventory.add(ClubItem(club().id, "kit_retro", "SHOP", clock.now()))
        seasons.createTrophy(Trophy(UUID.randomUUID(), club().id, TrophyKind.PROMOTION, 2, 1, clock.now()))
        val p = achievements.claim(user, "promoted")
        assertTrue(p.items.isEmpty()); assertEquals(20 + Cosmetics.DUPLICATE_GOLD, p.gold)
        Unit
    }

    @Test
    fun `best login streak is kept after the streak breaks`() = runBlocking<Unit> {
        setup()
        repeat(7) { daily(user); clock.advanceSeconds(86_400) }
        clock.advanceSeconds(3 * 86_400); daily(user)
        assertEquals(1, club().streak)
        val loyal = achievements.list(user).first { it.achievement.id == "loyal" }
        assertEquals(7, loyal.value); assertTrue(loyal.claimable)
    }

    @Test
    fun `premium pass grants cosmetics and an elite athlete at the last level`() = runBlocking<Unit> {
        setup()
        pass.view(user)
        val key = economyRepo.passes.keys.first()
        economyRepo.upsertPass(economyRepo.passes.getValue(key).copy(premium = true, xp = Economy.PASS_LEVELS * Economy.XP_PER_LEVEL))
        val squadBefore = athletes.rows.size
        val r = pass.claimLevels(user)
        assertTrue(r.items.containsAll(listOf("frame_gold", "kit_gold_trim", "kit_pinstripe", "cel_dance", "frame_flame")), r.items.toString())
        assertTrue(r.items.none { it.startsWith("decor_") })
        val elite = assertNotNull(r.elite)
        assertEquals(Rarity.ELITE, elite.rarity); assertEquals(Sport.FOOTBALL, elite.sport); assertTrue(elite.overall >= 60, "overall ${elite.overall}")
        assertEquals(squadBefore + 1, athletes.rows.size)
        assertFailsWith<ConflictException> { pass.claimLevels(user) }
        // next season the same track: items already owned turn into gold
        clubs.update(club().copy(seasonNo = club().seasonNo + 1))
        pass.view(user)
        val next = economyRepo.passes.keys.first { it.second == club().seasonNo }
        economyRepo.upsertPass(economyRepo.passes.getValue(next).copy(premium = true, xp = 15 * Economy.XP_PER_LEVEL))
        val goldBefore = club().gold
        val r2 = pass.claimLevels(user)
        assertTrue(r2.items.isEmpty()); assertNull(r2.elite)
        val levelGold = (1..15).sumOf { Economy.freeReward(it).gold + Economy.premiumReward(it).gold }
        assertEquals(goldBefore + levelGold + 2 * Cosmetics.DUPLICATE_GOLD, club().gold)   // frame_gold (10) + decor_statue (15)
    }

    @Test
    fun `bots wear varied kits`() {
        val kits = (0L until 50L).map { Cosmetics.botKit(it) }.toSet()
        assertTrue(kits.size >= 4); assertTrue(kits.all { Cosmetics.find(it)?.kind == CosmeticKind.KIT })
    }
}
