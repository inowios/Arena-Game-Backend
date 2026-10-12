package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.model.ObstacleState
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.util.UUID

class ObstacleTest {
    private val clock = FixedClock()
    private val clubs = FakeClubRepository()
    private val buildings = FakeBuildingRepository()
    private val obstacles = FakeObstacleRepository()
    private val service = ClubService(clubs, buildings, clock, obstacles = obstacles)
    private val create = CreateClubUseCase(clubs, buildings, clock, emptyList())
    private val get = GetClubUseCase(service)
    private val place = PlaceBuildingUseCase(service, buildings, clock)
    private val clear = ClearObstacleUseCase(service, obstacles, clock)
    private val speed = SpeedUpObstacleUseCase(service, obstacles, clock)
    private val collect = CollectObstacleUseCase(service, clubs, obstacles, clock)
    private val user = UUID.randomUUID()

    private fun newClub() = runBlocking { create(user, "Red Lions", "#AA0000", "#FFFFFF", "UTC") }

    @Test
    fun `a new club finds a few obstacles on free cells, more turn up over time up to the cap`() = runBlocking<Unit> {
        newClub()
        val st = get(user)
        assertEquals(Obstacles.START, st.obstacles.size)
        // never on a building, never on each other, inside the plot
        for (o in st.obstacles) {
            assertTrue(o.footprint.fits(st.gridSize))
            assertTrue(st.buildings.none { footprint(it.type, it.gridX, it.gridY, it.rotation).overlaps(o.footprint) })
            assertEquals(1, st.obstacles.count { it.footprint.overlaps(o.footprint) })
        }
        assertEquals(Obstacles.START, get(user).obstacles.size, "reading again spawns nothing")
        clock.advanceSeconds(Obstacles.SPAWN_SECONDS * 2 + 60)
        assertEquals(Obstacles.START + 2, get(user).obstacles.size)
        clock.advanceSeconds(Obstacles.SPAWN_SECONDS * 30)
        assertEquals(Obstacles.MAX, get(user).obstacles.size)
    }

    @Test
    fun `clearing takes a builder and coins, then the gold is picked up once`() = runBlocking<Unit> {
        newClub()
        clubs.update(clubs.rows.values.first().copy(coins = 50_000))
        val o = get(user).obstacles.first()
        val coins = clubs.rows.values.first().coins
        val c = clear(user, o.id)
        assertEquals(ObstacleState.CLEARING, c.state)
        assertEquals(coins - Obstacles.cost(o.kind, 1), clubs.rows.values.first().coins)
        assertEquals(1, get(user).busyBuilders)
        assertFailsWith<ConflictException> { clear(user, get(user).obstacles.first { it.id != o.id }.id) }   // the only builder is busy
        assertFailsWith<ConflictException> { collect(user, o.id) }
        clock.advanceSeconds(o.kind.seconds)
        assertEquals(ObstacleState.READY, get(user).obstacles.first { it.id == o.id }.state)
        assertEquals(0, get(user).busyBuilders)
        val gold = clubs.rows.values.first().gold
        val loot = collect(user, o.id)
        assertEquals(o.rewardGold, loot.gold); assertEquals(gold + o.rewardGold, loot.club.gold)
        assertTrue(get(user).obstacles.none { it.id == o.id })
        assertFailsWith<com.arenacity.domain.exception.NotFoundException> { collect(user, o.id) }
    }

    @Test
    fun `a clearing can be finished for gold, buildings never go on an obstacle`() = runBlocking<Unit> {
        newClub()
        clubs.update(clubs.rows.values.first().copy(coins = 50_000, gold = 100))
        val o = get(user).obstacles.first()
        clear(user, o.id)
        val s = speed(user, o.id)
        assertEquals(ObstacleState.READY, s.state)
        assertTrue(clubs.rows.values.first().gold < 100)
        val other = get(user).obstacles.first { it.state == ObstacleState.IDLE }
        assertFailsWith<ConflictException> { place(user, BuildingType.DECOR, other.gridX, other.gridY, 0) }
    }

    @Test
    fun `cost grows with the stadium, gold depends on the kind`() {
        assertTrue(Obstacles.cost(ObstacleKind.ROCK, 10) > Obstacles.cost(ObstacleKind.ROCK, 1))
        val r = kotlin.random.Random(3)
        repeat(200) { assertTrue(Obstacles.gold(ObstacleKind.BUSH, r) in 1..8); assertTrue(Obstacles.gold(ObstacleKind.SHED, r) in 3..20) }
        assertEquals(0, Obstacles.due(0, 100, Obstacles.MAX))
    }
}
