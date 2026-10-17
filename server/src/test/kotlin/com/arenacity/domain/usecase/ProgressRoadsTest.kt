package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.domain.usecase.economy.AchievementService
import com.arenacity.domain.usecase.economy.CosmeticsService
import com.arenacity.domain.usecase.progress.*
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.util.UUID

class ProgressRoadsTest {
    private val clock = FixedClock()
    private val clubs = FakeClubRepository()
    private val athletes = FakeAthleteRepository()
    private val inventory = FakeInventoryRepository()
    private val spots = FakeClubSpotRepository()
    private val service = ClubService(clubs, clock, spots)
    private val cosmetics = CosmeticsService(inventory, clock)
    private val create = CreateClubUseCase(clubs, clock, emptyList())
    private val roads = RoadUseCases(service, clubs, FakeRoadRepository(), cosmetics, athletes, clock)
    private val collections = CollectionsUseCases(service, cosmetics)
    private val museum = MuseumService(clubs, service, AchievementService(FakeAchievementRepository(), service, FakeSeasonRepository()))
    private val user = UUID.randomUUID()
    private fun club() = clubs.rows.values.first { it.userId == user }

    private fun newClub(trophies: Int = 0, level: Int = 1) = runBlocking {
        create(user, "Road FC", "#AA0000", "#FFFFFF", "UTC"); clubs.update(club().copy(trophies = trophies, level = level))
    }

    @Test
    fun `trophy road steps pay once each, only once reached`() = runBlocking<Unit> {
        newClub(trophies = 120)
        val view = roads.list(user).first { it.road == Road.TROPHY }
        assertEquals(listOf(true, true, false), view.steps.take(3).map { it.reached })
        val coins = club().coins
        val r = roads.claim(user, Road.TROPHY, 1)
        assertEquals(300, r.coins); assertEquals(coins + 300, club().coins)
        assertFailsWith<ConflictException> { roads.claim(user, Road.TROPHY, 1) }
        assertFailsWith<ValidationException> { roads.claim(user, Road.TROPHY, 3) }
        val gold = club().gold
        roads.claim(user, Road.TROPHY, 2)
        assertEquals(gold + 10, club().gold)
        // dropping below a claimed step keeps it claimed
        clubs.update(club().copy(trophies = 10))
        assertTrue(roads.list(user).first { it.road == Road.TROPHY }.steps[0].claimed)
    }

    @Test
    fun `road items go to the inventory, a second copy turns into gold, a pack brings a player`() = runBlocking<Unit> {
        newClub(trophies = 600)
        val r = roads.claim(user, Road.TROPHY, 4)
        assertEquals("decor_led_pole", r.item)
        assertTrue("decor_led_pole" in cosmetics.owned(club().id))
        val pack = roads.claim(user, Road.TROPHY, 6)
        assertNotNull(pack.athlete)
        assertEquals(1, athletes.listByClub(club().id).size)
        // the level road: decor_bench at level 3; owned already -> gold instead
        clubs.update(club().copy(level = 3))
        cosmetics.grant(club().id, Cosmetics.find("decor_bench")!!, CosmeticSource.SHOP)
        val g = club().gold
        val lv = roads.claim(user, Road.LEVEL, 2)
        assertNull(lv.item); assertEquals(g + Cosmetics.DUPLICATE_GOLD, club().gold)
    }

    @Test
    fun `a collection is complete once every item is owned`() = runBlocking<Unit> {
        newClub()
        val (sets0, pct0) = collections.list(user)
        assertEquals(0, pct0); assertTrue(sets0.none { it.complete })
        for (id in Collections.ALL.first { it.id == "park" }.items) cosmetics.grant(club().id, Cosmetics.find(id)!!, CosmeticSource.SHOP)
        val (sets, pct) = collections.list(user)
        assertEquals(4, pct); assertTrue(sets.single { it.complete }.set.id == "park")
    }

    @Test
    fun `the hall of fame holds as many honours as the stadium level allows`() = runBlocking<Unit> {
        newClub()
        val v1 = museum.of(club().id)
        assertEquals(1, v1.level); assertEquals(3, v1.capacity)                     // stadium 1: three exhibits
        spots.open(club().id, MapSpots.ACADEMY, MapSpots.HOSPITAL)
        val v3 = museum.of(club().id)
        assertEquals(3, v3.level); assertEquals(MuseumRules.capacity(3), v3.capacity); assertEquals(9, v3.capacity)
        assertTrue(v3.exhibits.size <= 9)
    }
}

class SeasonThemesTest {
    @Test
    fun `each time of year has its theme and its own decoration`() {
        assertEquals("autumn", SeasonThemes.of(java.time.LocalDate.of(2026, 10, 10)).id)
        assertEquals("winter", SeasonThemes.of(java.time.LocalDate.of(2027, 1, 3)).id)
        assertEquals("spring", SeasonThemes.of(java.time.LocalDate.of(2027, 4, 1)).id)
        assertEquals("summer", SeasonThemes.of(java.time.LocalDate.of(2027, 7, 1)).id)
        for (t in SeasonThemes.ALL) assertEquals("theme:${t.id}", Cosmetics.find(t.item)?.unlock)
        // road cosmetics exist for every road item
        for (s in Roads.TROPHY + Roads.LEVEL) s.reward.item?.let { assertNotNull(Cosmetics.find(it), it) }
    }
}

class FakeRoadRepository : RoadRepository {
    private val rows = mutableSetOf<Triple<UUID, Road, Int>>()
    override suspend fun claimed(clubId: UUID) = rows.filter { it.first == clubId }.groupBy({ it.second }, { it.third }).mapValues { it.value.toSet() }
    override suspend fun claim(clubId: UUID, road: Road, index: Int, at: java.time.Instant) = rows.add(Triple(clubId, road, index))
}
