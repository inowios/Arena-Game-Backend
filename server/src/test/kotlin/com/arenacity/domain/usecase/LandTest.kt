package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.InsufficientFundsException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.repository.LandRepository
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class FakeLandRepository : LandRepository {
    val rows = mutableMapOf<UUID, MutableSet<Pair<Int, Int>>>()
    override suspend fun listByClub(clubId: UUID) = rows[clubId]?.toList() ?: emptyList()
    override suspend fun add(clubId: UUID, col: Int, row: Int, at: Instant) { rows.getOrPut(clubId) { mutableSetOf() }.add(col to row) }
    override suspend fun remove(clubId: UUID, col: Int, row: Int) { rows[clubId]?.remove(col to row) }
}

/**
 * The land round the club (the eras map, 13 October): the plot to start, parcels bought one by one next to it. Not for
 * sale since the archipelago (12 October): the old path stays behind [LandUseCases]' and [ClubService]'s `open` switches.
 * The plot is the central 4×4 parcels since v2: every club, old ones too, owns all sixteen.
 */
class LandTest {
    private val clock = FixedClock()
    private val clubs = FakeClubRepository()
    private val buildings = FakeBuildingRepository()
    private val land = FakeLandRepository()
    private val service = ClubService(clubs, buildings, clock, land = land)
    private val create = CreateClubUseCase(clubs, buildings, clock, emptyList())
    private val get = GetClubUseCase(service)
    private val place = PlaceBuildingUseCase(service, buildings, clock)
    private val roads = PlaceFencesUseCase(service, buildings, clock)
    private val closed = LandUseCases(service, land, clock)
    // the old buying path: bought parcels count as land only while land is for sale
    private val openService = ClubService(clubs, buildings, clock, land = land, landOpen = true)
    private val buy = LandUseCases(openService, land, clock, open = true)
    private val placeOpen = PlaceBuildingUseCase(openService, buildings, clock)
    private val user = UUID.randomUUID()

    private fun newClub(coins: Long = 1_000_000) = runBlocking {
        create(user, "Red Lions", "#AA0000", "#FFFFFF", "Europe/Kyiv")
        clubs.update(clubs.rows.values.first().copy(coins = coins, trophies = 100_000))
    }

    @Test
    fun `the field is six by six parcels round the plot, the plot is the sixteen home parcels`() {
        assertEquals(16, Land.HOME.size); assertTrue(Land.HOME.all { (c, r) -> c in 1..4 && r in 1..4 })
        assertEquals(1 to 1, Land.parcelOf(-9, -9)); assertEquals(4 to 4, Land.parcelOf(38, 38)); assertEquals(0 to 0, Land.parcelOf(-21, -21))
        // the plot is the grid the client draws: 48 cells round the old square's middle, the stadium's centre stays at 15
        assertEquals(48, Grid.PLOT); assertEquals(Land.cell(1), Grid.lo(Grid.PLOT)); assertEquals(Land.cell(5), Grid.hi(Grid.PLOT)); assertEquals(15, Grid.CENTRE)
        assertTrue(Land.fits(Footprint(-9, -9, 48, 48), Land.HOME))
        assertFalse(Land.fits(Footprint(-10, 3, 2, 2), Land.HOME)); assertFalse(Land.fits(Footprint(37, 37, 3, 2), Land.HOME))
        assertFalse(Land.fits(Footprint(-22, 0, 1, 1), Land.HOME + (0 to 1)))
        assertEquals(listOf(4_000L, 6_200L, 9_600L), (0..2).map { Land.price(it) })
        assertFalse(Land.BUYING); assertFalse(Land.canBuy(5, 2, Land.HOME))
        assertTrue(Land.canBuy(5, 2, Land.HOME, open = true)); assertFalse(Land.canBuy(5, 5, Land.HOME, open = true)); assertFalse(Land.canBuy(2, 2, Land.HOME, open = true))
        // with buying off a bought parcel is not land any more (the ring round the plot is sea); while open it is
        assertEquals(Land.HOME, Land.owned(listOf(0 to 2, 3 to 4)))
        assertEquals(Land.HOME + (0 to 2), Land.owned(listOf(0 to 2, 3 to 4, 9 to 9), open = true))
    }

    @Test
    fun `land round the plot is not for sale since the archipelago`() = runBlocking<Unit> {
        newClub()
        val before = clubs.rows.values.first().coins
        val e = assertFailsWith<ValidationException> { closed.buy(user, 5, 2) }
        assertEquals("Land is not for sale", e.template)
        assertEquals("Земля вокруг твоего участка не продаётся: клуб растёт на островах", e.localized("ru"))
        assertEquals(before, clubs.rows.values.first().coins)
        val st = get(user)
        assertEquals(Land.HOME, st.parcels)
        val view = closed.view(st)
        assertEquals(Land.HOME, view.parcels); assertEquals(0, view.nextPrice); assertFalse(view.buyable)
        assertEquals(4_000, buy.view(st).nextPrice)
        Unit
    }

    @Test
    fun `a parcel is bought next to owned land, paid, and opens the land to building`() = runBlocking<Unit> {
        newClub()
        val before = clubs.rows.values.first().coins
        assertFailsWith<ValidationException> { buy.buy(user, 5, 5) }                       // does not touch the land
        assertFailsWith<ConflictException> { buy.buy(user, 2, 2) }                         // the plot
        assertFailsWith<ConflictException> { buy.buy(user, 4, 1) }                         // the plot's new parcels too
        assertFailsWith<ValidationException> { buy.buy(user, 6, 2) }                       // off the field
        assertFailsWith<ValidationException> { placeOpen(user, BuildingType.ACADEMY, 40, 5, 0) }  // not bought yet
        val v = buy.buy(user, 5, 2)
        assertTrue((5 to 2) in v.parcels); assertEquals(6_200, v.nextPrice)
        assertEquals(before - 4_000, clubs.rows.values.first().coins)
        assertEquals(Land.HOME + (5 to 2), openService.state(user).parcels)
        placeOpen(user, BuildingType.ACADEMY, 40, 5, 0)                                     // on the new parcel
        assertFailsWith<ConflictException> { buy.buy(user, 5, 2) }
        buy.buy(user, 5, 1); buy.buy(user, 5, 0)                                            // next to the bought one
        Unit
    }

    @Test
    fun `no coins, no land`() = runBlocking<Unit> {
        newClub(coins = 100)
        assertFailsWith<InsufficientFundsException> { buy.buy(user, 5, 2) }
        Unit
    }

    @Test
    fun `an older club owns the sixteen home parcels, a parcel it bought before the archipelago is sea, its buildings move onto the plot and its roads go`() = runBlocking<Unit> {
        newClub()
        val club = clubs.rows.values.first()
        // the v1 plot's buyer: (3, 4) is home now, (0, 2) lies in the sea; an academy and a road were built out there
        land.add(club.id, 3, 4, Instant.EPOCH); land.add(club.id, 0, 2, Instant.EPOCH)
        val academy = buildings.create(com.arenacity.domain.model.Building(UUID.randomUUID(), club.id, BuildingType.ACADEMY, 2, -20, 5, 0,
            com.arenacity.domain.model.BuildingState.IDLE, null, clock.now(), clock.now(), clock.now()))
        val road = buildings.create(com.arenacity.domain.model.Building(UUID.randomUUID(), club.id, BuildingType.ROAD, 1, -15, 20, 0,
            com.arenacity.domain.model.BuildingState.IDLE, null, clock.now(), clock.now(), clock.now()))
        val st = get(user)
        assertEquals(Land.HOME, st.parcels)
        assertEquals(16, closed.view(st).parcels.size)
        // the academy stands on the plot now, at the free spot nearest where it stood, the level kept; the move is
        // stored; the road out there is gone (a stray tile on the plot's edge would join nothing)
        val a = st.buildings.first { it.id == academy.id }
        assertTrue(Land.fits(footprint(a.type, a.gridX, a.gridY, a.rotation), Land.HOME)); assertEquals(-9, a.gridX); assertEquals(5, a.gridY); assertEquals(2, a.level)
        assertTrue(st.buildings.none { it.id == road.id }); assertNull(buildings.findById(road.id))
        assertEquals(-9, buildings.findById(academy.id)!!.gridX)
        // nothing more moves on the next read; building on the sea parcel is refused, on the plot's new land it works
        assertEquals(st.buildings.map { it.gridX to it.gridY }, get(user).buildings.map { it.gridX to it.gridY })
        buildings.setStadium(club.id, 4)   // roads open at stadium 4
        assertEquals("Outside your land", assertFailsWith<ValidationException> { roads(user, listOf(-15 to 30), BuildingType.ROAD) }.template)
        place(user, BuildingType.TRAINING_CENTER, 30, 30, 0)
        Unit
    }

    @Test
    fun `roads are drawn on owned land like fences`() = runBlocking<Unit> {
        newClub()
        // the stadium opens roads at level 4: lift it straight in the data
        val pitch = buildings.listByClub(clubs.rows.values.first().id).first { it.type == BuildingType.PITCH }
        buildings.move(pitch.copy(level = 4))
        val laid = roads(user, listOf(5 to 20, 6 to 20, 7 to 20), BuildingType.ROAD)
        assertEquals(3, laid.size); assertTrue(laid.all { it.type == BuildingType.ROAD })
        assertFailsWith<ValidationException> { roads(user, listOf(-10 to 20), BuildingType.ROAD) }
        assertFailsWith<ValidationException> { roads(user, listOf(8 to 20), BuildingType.CAFE) }
        Unit
    }
}
