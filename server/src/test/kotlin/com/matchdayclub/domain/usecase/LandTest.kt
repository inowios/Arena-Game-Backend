package com.matchdayclub.domain.usecase

import com.matchdayclub.domain.exception.ConflictException
import com.matchdayclub.domain.exception.InsufficientFundsException
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.repository.LandRepository
import com.matchdayclub.domain.rules.*
import com.matchdayclub.domain.usecase.club.*
import com.matchdayclub.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class FakeLandRepository : LandRepository {
    val rows = mutableMapOf<UUID, MutableSet<Pair<Int, Int>>>()
    override suspend fun listByClub(clubId: UUID) = rows[clubId]?.toList() ?: emptyList()
    override suspend fun add(clubId: UUID, col: Int, row: Int, at: Instant) { rows.getOrPut(clubId) { mutableSetOf() }.add(col to row) }
}

/** The land round the club (the eras map, 13 October): the plot to start, parcels bought one by one next to it. */
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
    private val buy = LandUseCases(service, land, clock)
    private val user = UUID.randomUUID()

    private fun newClub(coins: Long = 1_000_000) = runBlocking {
        create(user, "Red Lions", "#AA0000", "#FFFFFF", "Europe/Kyiv")
        clubs.update(clubs.rows.values.first().copy(coins = coins, trophies = 100_000))
    }

    @Test
    fun `the field is six by six parcels round the plot, the plot is the four home parcels`() {
        assertEquals(2 to 2, Land.parcelOf(3, 3)); assertEquals(3 to 3, Land.parcelOf(26, 26)); assertEquals(0 to 0, Land.parcelOf(-21, -21))
        assertTrue(Land.fits(Footprint(3, 3, 24, 24), Land.HOME))
        assertFalse(Land.fits(Footprint(2, 3, 2, 2), Land.HOME))
        assertFalse(Land.fits(Footprint(-22, 0, 1, 1), Land.HOME + (0 to 1)))
        assertEquals(listOf(4_000L, 6_200L, 9_600L), (0..2).map { Land.price(it) })
    }

    @Test
    fun `a parcel is bought next to owned land, paid, and opens the land to building`() = runBlocking<Unit> {
        newClub()
        val before = clubs.rows.values.first().coins
        assertFailsWith<ValidationException> { buy.buy(user, 0, 0) }                       // does not touch the land
        assertFailsWith<ConflictException> { buy.buy(user, 2, 2) }                         // the plot
        assertFailsWith<ValidationException> { buy.buy(user, 6, 2) }                       // off the field
        assertFailsWith<ValidationException> { place(user, BuildingType.ACADEMY, 27, 5, 0) }  // not bought yet
        val v = buy.buy(user, 4, 2)
        assertTrue((4 to 2) in v.parcels); assertEquals(6_200, v.nextPrice)
        assertEquals(before - 4_000, clubs.rows.values.first().coins)
        assertEquals(Land.HOME + (4 to 2), get(user).parcels)
        place(user, BuildingType.ACADEMY, 27, 5, 0)                                         // on the new parcel
        assertFailsWith<ConflictException> { buy.buy(user, 4, 2) }
        buy.buy(user, 5, 2)                                                                 // next to the bought one
        Unit
    }

    @Test
    fun `no coins, no land`() = runBlocking<Unit> {
        newClub(coins = 100)
        assertFailsWith<InsufficientFundsException> { buy.buy(user, 1, 2) }
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
        assertFailsWith<ValidationException> { roads(user, listOf(-5 to 20), BuildingType.ROAD) }
        assertFailsWith<ValidationException> { roads(user, listOf(8 to 20), BuildingType.CAFE) }
        Unit
    }
}
