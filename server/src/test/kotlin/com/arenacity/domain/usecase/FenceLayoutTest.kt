package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.BuildingState
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.util.UUID

class FenceLayoutTest {
    private val clock = FixedClock()
    private val clubs = FakeClubRepository()
    private val buildings = FakeBuildingRepository()
    // the old square with a corner cut off: the cells these tests name are on it or off it whatever mask the server ships
    private val service = ClubService(clubs, buildings, clock, lands = TestLand.NOTCHED)
    private val create = CreateClubUseCase(clubs, buildings, clock, emptyList(), lands = TestLand.NOTCHED)
    private val get = GetClubUseCase(service)
    private val fences = PlaceFencesUseCase(service, buildings, clock)
    private val upFences = UpgradeFencesUseCase(service, buildings, clock)
    private val upgrade = UpgradeBuildingUseCase(service, buildings, clock)
    private val remove = RemoveBuildingUseCase(service, buildings)
    private val applyLayout = ApplyLayoutUseCase(service, buildings, clock)
    private val layouts = LayoutUseCases(service, FakeLayoutRepository(), applyLayout, clock)
    private val user = UUID.randomUUID()

    private fun newClub(coins: Long = 100_000) = runBlocking { create(user, "Red Lions", "#AA0000", "#FFFFFF", "UTC"); clubs.update(clubs.rows.values.first().copy(coins = coins)) }
    private val coins get() = clubs.rows.values.first().coins

    @Test
    fun `a dragged line of fence is built at once, paid together, without a builder`() = runBlocking<Unit> {
        newClub()
        val line = (0 until 4).map { 8 + 2 * it to 28 }   // one tile (2×2 cells) each, on tile corners
        val placed = fences(user, line)
        assertEquals(4, placed.size)
        assertTrue(placed.all { it.type == BuildingType.FENCE && it.state == BuildingState.IDLE && it.level == 1 })
        assertEquals(100_000 - 4 * 30L, coins)
        assertEquals(0, get(user).busyBuilders)
    }

    @Test
    fun `fence cells are checked like placements and limited by the stadium`() = runBlocking<Unit> {
        newClub()
        assertFailsWith<ConflictException> { fences(user, listOf(4 to 22)) }         // the ticket office
        assertFailsWith<ValidationException> { fences(user, listOf(40 to 30)) }      // outside the plot (cells -9..38)
        assertFailsWith<ValidationException> { fences(user, listOf(24 to 28, 24 to 28)) }
        assertEquals("Off the build grid", assertFailsWith<ValidationException> { fences(user, listOf(25 to 28)) }.template)   // off a tile corner
        val limit = Stadium.fenceCount(1)
        val tooMany = (0 until limit + 1).map { 24 + 2 * (it % 6) to 28 + 2 * (it / 6) }
        val e = assertFailsWith<ValidationException> { fences(user, tooMany) }
        assertEquals(listOf<Any>(2), e.args.toList())
        assertEquals(100_000, coins, "nothing charged on a refused line")
    }

    @Test
    fun `fences upgrade together and instantly up to the stadium's cap`() = runBlocking<Unit> {
        newClub()
        val ids = fences(user, listOf(8 to 28, 10 to 28, 12 to 28)).map { it.id }
        assertFailsWith<ValidationException> { upFences(user, ids) }   // level 2 needs stadium 2
        buildings.setStadium(clubs.rows.values.first().id, 3)
        val before = coins
        val up = upFences(user, ids)
        assertTrue(up.all { it.level == 2 && it.state == BuildingState.IDLE })
        assertEquals(before - 3 * 150L, coins)
        upgrade(user, ids[0])                                            // one by one works too, still instant
        assertEquals(3, get(user).buildings.first { it.id == ids[0] }.level)
        assertEquals(BuildingState.IDLE, get(user).buildings.first { it.id == ids[0] }.state)
        assertEquals(0, get(user).busyBuilders)
        remove(user, ids[1])
        assertEquals(2, get(user).buildings.count { it.type == BuildingType.FENCE })
    }

    @Test
    fun `a fence line next to an income building adds a little atmosphere`() = runBlocking<Unit> {
        newClub()
        val office = get(user).buildings.first { it.type == BuildingType.TICKET_OFFICE }   // (4,22) 4x4
        val before = Atmosphere.of(get(user).buildings).getValue(office.id)
        fences(user, listOf(4 to 20, 6 to 20, 8 to 22, 8 to 24))
        val after = Atmosphere.of(get(user).buildings).getValue(office.id)
        assertEquals(4, after.fence)                                  // all four touching it
        assertEquals(before.total + 4, after.total)
    }

    @Test
    fun `edit mode swaps two buildings in one go and refuses overlaps`() = runBlocking<Unit> {
        newClub()
        val a = fences(user, listOf(24 to 28)).single(); val b = fences(user, listOf(26 to 28)).single()
        applyLayout(user, listOf(Placement(a.id, 26, 28, 0), Placement(b.id, 24, 28, 0)))
        val st = get(user)
        assertEquals(26, st.buildings.first { it.id == a.id }.gridX)
        assertEquals(24, st.buildings.first { it.id == b.id }.gridX)
        assertFailsWith<ConflictException> { applyLayout(user, listOf(Placement(a.id, 24, 28, 0))) }   // onto b
        val office = st.buildings.first { it.type == BuildingType.TICKET_OFFICE }
        assertFailsWith<ValidationException> { applyLayout(user, listOf(Placement(office.id, 36, 10, 0))) }   // 4 wide: off the plot's edge (cell 38)
        assertFailsWith<ValidationException> { applyLayout(user, listOf(Placement(office.id, 25, 28, 0))) }   // off a tile corner
    }

    @Test
    fun `a saved layout brings the club back the way it was`() = runBlocking<Unit> {
        newClub()
        val f = fences(user, listOf(24 to 28)).single()
        layouts.save(user, 1, "Matchday")
        applyLayout(user, listOf(Placement(f.id, 28, 28, 0)))
        assertEquals(28, get(user).buildings.first { it.id == f.id }.gridX)
        layouts.applySlot(user, 1)
        assertEquals(24, get(user).buildings.first { it.id == f.id }.gridX)
        assertEquals(listOf("Matchday"), layouts.list(user).map { it.name })
        assertFailsWith<ValidationException> { layouts.save(user, 4, "x") }
        // a building put since on the saved spot makes the layout refuse, kindly
        val g = fences(user, listOf(28 to 28)).single()
        applyLayout(user, listOf(Placement(f.id, 30, 28, 0), Placement(g.id, 24, 28, 0)))
        assertFailsWith<ConflictException> { layouts.applySlot(user, 1) }
    }
}

class FakeLayoutRepository : com.arenacity.domain.repository.LayoutRepository {
    private val rows = mutableMapOf<Pair<UUID, Int>, LayoutSlot>()
    override suspend fun list(clubId: UUID) = rows.filterKeys { it.first == clubId }.values.sortedBy { it.slot }
    override suspend fun save(clubId: UUID, layout: LayoutSlot) { rows[clubId to layout.slot] = layout }
}
