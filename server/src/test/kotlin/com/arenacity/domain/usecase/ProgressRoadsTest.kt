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
    private val inventory = FakeInventoryRepository()
    private val spots = FakeClubSpotRepository()
    private val service = ClubService(clubs, clock, spots)
    private val cosmetics = CosmeticsService(inventory, clock)
    private val create = CreateClubUseCase(clubs, clock, emptyList())
    private val roads = RoadUseCases(service, clubs, FakeRoadRepository(), cosmetics, clock)
    private val collections = CollectionsUseCases(service, cosmetics)
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
    fun `road items go to the inventory, a second copy turns into gold, no step gives a player or a decoration`() = runBlocking<Unit> {
        newClub(trophies = 2_300)
        val r = roads.claim(user, Road.TROPHY, 13)   // 2 200: a kit
        assertEquals("kit_halves", r.item)
        assertTrue("kit_halves" in cosmetics.owned(club().id))
        val g0 = club().gold
        val step6 = roads.claim(user, Road.TROPHY, 6)   // 500: gold, not a pack
        assertEquals(30, step6.gold); assertEquals(g0 + 30, club().gold)
        assertTrue((Roads.TROPHY + Roads.LEVEL).none { it.reward.item?.startsWith("decor_") == true })
        // the level road's last step: frame_gold; owned already -> gold instead
        clubs.update(club().copy(level = 10))
        cosmetics.grant(club().id, Cosmetics.find("frame_gold")!!, CosmeticSource.SHOP)
        val g = club().gold
        val lv = roads.claim(user, Road.LEVEL, 9)
        assertNull(lv.item); assertEquals(g + 150 + Cosmetics.DUPLICATE_GOLD, club().gold)
    }

    @Test
    fun `a collection is complete once every item is owned`() = runBlocking<Unit> {
        newClub()
        val (sets0, pct0) = collections.list(user)
        assertEquals(0, pct0); assertTrue(sets0.none { it.complete })
        for (id in Collections.ALL.first { it.id == "frames" }.items) cosmetics.grant(club().id, Cosmetics.find(id)!!, CosmeticSource.SHOP)
        val (sets, pct) = collections.list(user)
        assertEquals(2, pct); assertTrue(sets.single { it.complete }.set.id == "frames")
        assertTrue(Collections.ALL.none { s -> s.items.any { it.startsWith("decor_") } })   // no town decorations
    }
}

class SeasonThemesTest {
    @Test
    fun `each time of year has its theme, no decoration any more`() {
        assertEquals("autumn", SeasonThemes.of(java.time.LocalDate.of(2026, 10, 10)).id)
        assertEquals("winter", SeasonThemes.of(java.time.LocalDate.of(2027, 1, 3)).id)
        assertEquals("spring", SeasonThemes.of(java.time.LocalDate.of(2027, 4, 1)).id)
        assertEquals("summer", SeasonThemes.of(java.time.LocalDate.of(2027, 7, 1)).id)
        for (t in SeasonThemes.ALL) assertEquals("", t.item)
        // road cosmetics exist for every road item
        for (s in Roads.TROPHY + Roads.LEVEL) s.reward.item?.let { assertNotNull(Cosmetics.find(it), it) }
    }
}

class FakeRoadRepository : RoadRepository {
    private val rows = mutableSetOf<Triple<UUID, Road, Int>>()
    override suspend fun claimed(clubId: UUID) = rows.filter { it.first == clubId }.groupBy({ it.second }, { it.third }).mapValues { it.value.toSet() }
    override suspend fun claim(clubId: UUID, road: Road, index: Int, at: java.time.Instant) = rows.add(Triple(clubId, road, index))
}
