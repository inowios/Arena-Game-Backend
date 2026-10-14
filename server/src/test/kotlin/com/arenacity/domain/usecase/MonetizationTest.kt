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
    private val users = FakeUserRepository(); private val clubs = FakeClubRepository(); private val buildings = FakeBuildingRepository()
    private val athletes = FakeAthleteRepository(); private val trainings = FakeTrainingRepository()
    private val leagues = FakeLeagueRepository(); private val fixtures = FakeFixtureRepository(); private val economyRepo = FakeEconomyRepository()
    private val inventory = FakeInventoryRepository(); private val achievementsRepo = FakeAchievementRepository(); private val seasons = FakeSeasonRepository()
    private val service = ClubService(clubs, buildings, clock)
    private val economy = EconomyService(economyRepo, leagues, clock, achievements = achievementsRepo)
    private val cosmetics = CosmeticsService(inventory, clock)
    private val bots = BotService(users, clubs, athletes, clock)
    private val leagueService = LeagueService(leagues, fixtures, clubs, bots, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock), JoinPublicLeagueInitializer(clubs, leagueService, clock)))
    private val place = PlaceBuildingUseCase(service, buildings, clock, cosmetics = cosmetics)
    private val speed = SpeedUpUseCase(service, buildings, clock)
    private val collect = CollectIncomeUseCase(service, clubs, buildings, clock, economy)
    private val squads = SquadService(service, athletes, trainings, clock)
    private val startTraining = StartTrainingUseCase(service, squads, athletes, trainings, clock, economy)
    private val speedTraining = SpeedUpTrainingUseCase(service, squads, trainings, clock)
    private val speedMedical = SpeedUpMedicalUseCase(service, clubs, clock)
    private val heal = HealAthleteUseCase(service, squads, clubs, athletes, clock)
    private val coinPacks = BuyCoinPackUseCase(service, clubs, clock)
    private val achievementService = AchievementService(achievementsRepo, buildings, seasons)
    private val achievements = AchievementUseCases(service, clubs, achievementsRepo, achievementService, cosmetics, clock)
    private val pass = SeasonPassUseCases(service, clubs, economyRepo, economy, clock, cosmetics = cosmetics, athletes = athletes)
    private val daily = ClaimDailyRewardUseCase(service, clubs, economy, clock)
    private val ads = AdRewardUseCase(service, clubs, buildings, economy, clock, repo = economyRepo)
    private val user = UUID.randomUUID()

    private fun club() = clubs.rows.values.first { it.userId == user }

    private fun setup(gold: Int = 1_000, level: Int = 3, vararg types: BuildingType) = runBlocking {
        create(user, "Lions", "#AA0000", "#FFFFFF", "UTC")
        clubs.update(club().copy(coins = 50_000, gold = gold, level = level)); buildings.setStadium(club().id, 3)
        types.forEachIndexed { i, t -> val b = place(user, t, i * 10, 26, 0); speed(user, b.id) }   // the big footprints: a row below the ticket office
        clubs.update(club().copy(gold = gold))
    }

    @Test
    fun `decor looks at placement need an owned decor item`() = runBlocking<Unit> {
        setup()
        assertFailsWith<ConflictException> { place(user, BuildingType.DECOR, 6, 36, 0, "decor_fountain") }
        assertFailsWith<ValidationException> { place(user, BuildingType.ACADEMY, 8, 36, 0, "decor_tree") }
        cosmetics.grant(club().id, Cosmetics.find("decor_fountain")!!, CosmeticSource.ROAD)   // earned, e.g. on the club road
        val d = place(user, BuildingType.DECOR, 6, 36, 0, "decor_fountain")
        assertEquals("decor_fountain", d.variant)
        Unit
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
        val office = buildings.rows.values.first { it.type == BuildingType.TICKET_OFFICE }
        repeat(10) { clock.advanceSeconds(3600); collect(user, office.id) }
        val list = achievements.list(user)
        val tycoon = list.first { it.achievement.id == "tycoon" }
        assertEquals(10, tycoon.value); assertEquals(1, tycoon.reachedTier); assertTrue(tycoon.claimable)
        val growing = list.first { it.achievement.id == "growing_club" }
        assertEquals(1, growing.reachedTier)                                           // club level 3
        val claim = achievements.claim(user, "tycoon")
        assertEquals(5, claim.gold); assertEquals(5, club().gold)
        assertFailsWith<ConflictException> { achievements.claim(user, "tycoon") }
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
        assertTrue(r.items.containsAll(listOf("frame_gold", "decor_statue", "kit_pinstripe", "cel_dance", "decor_banner")), r.items.toString())
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
    fun `training and medical speed-ups cost gold by remaining time`() = runBlocking<Unit> {
        setup(gold = 100, level = 3, BuildingType.TRAINING_CENTER, BuildingType.MEDICAL_CENTER)
        val a = athletes.rows.values.first { it.clubId == club().id && it.sport == Sport.FOOTBALL && it.position.name != "GK" && it.attack < 55 }
        val t = startTraining(user, a.id, AttributeKind.ATTACK)
        clock.advanceSeconds(10 * 60)
        val (trained, c) = speedTraining(user, t.id)
        assertEquals(100 - BuildingCatalog.speedUpGold(50 * 60), c.gold)                   // 50 min left → 5 gold
        assertEquals(a.attack + t.delta, trained.attack)
        assertTrue(trainings.rows.isEmpty())
        assertFailsWith<NotFoundException> { speedTraining(user, t.id) }
        assertFailsWith<ConflictException> { speedMedical(user) }                        // nothing to speed up
        athletes.update(a.copy(injuredUntil = clock.now().plusSeconds(86_400)))
        heal(user, a.id)
        val before = club().gold
        val after = speedMedical(user)
        assertNull(after.medicalNextAt); assertTrue(after.gold < before)
        val b = athletes.rows.values.first { it.clubId == club().id && it.id != a.id }
        athletes.update(b.copy(injuredUntil = clock.now().plusSeconds(86_400)))
        heal(user, b.id)                                                                  // healed right away
        Unit
    }

    @Test
    fun `verified ad rewards are granted once per transaction`() = runBlocking<Unit> {
        setup()
        val c = assertNotNull(ads.grantVerified(user, AdPlacement.DOUBLE_INCOME, null, "tx-1"))
        assertNotNull(c.doubleIncomeUntil)
        assertNull(ads.grantVerified(user, AdPlacement.DOUBLE_INCOME, null, "tx-1"))
        assertFailsWith<ConflictException> { ads.grantVerified(user, AdPlacement.DOUBLE_INCOME, null, "tx-2") }   // cooldown
        Unit
    }

    @Test
    fun `bots wear varied kits`() {
        val kits = (0L until 50L).map { Cosmetics.botKit(it) }.toSet()
        assertTrue(kits.size >= 4); assertTrue(kits.all { Cosmetics.find(it)?.kind == CosmeticKind.KIT })
    }
}
