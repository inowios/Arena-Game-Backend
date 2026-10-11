package com.matchdayclub.domain.usecase

import com.matchdayclub.domain.exception.ConflictException
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.BuildingState
import com.matchdayclub.domain.rules.*
import com.matchdayclub.domain.usecase.club.*
import com.matchdayclub.testutil.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.util.UUID

class FenceLayoutTest {
    private val clock = FixedClock()
    private val clubs = FakeClubRepository()
    private val buildings = FakeBuildingRepository()
    private val service = ClubService(clubs, buildings, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, emptyList())
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
        val line = (11..16).map { it to 19 }
        val placed = fences(user, line)
        assertEquals(6, placed.size)
        assertTrue(placed.all { it.type == BuildingType.FENCE && it.state == BuildingState.IDLE && it.level == 1 })
        assertEquals(100_000 - 6 * 30L, coins)
        assertEquals(0, get(user).busyBuilders)
    }

    @Test
    fun `fence cells are checked like placements and limited by the stadium`() = runBlocking<Unit> {
        newClub()
        assertFailsWith<ConflictException> { fences(user, listOf(9 to 9)) }          // the ticket office
        assertFailsWith<ValidationException> { fences(user, listOf(30 to 31)) }      // outside the square
        assertFailsWith<ValidationException> { fences(user, listOf(10 to 20, 10 to 20)) }
        val limit = Stadium.fenceCount(1)
        val tooMany = (0 until limit + 1).map { 9 + it % 12 to 19 + it / 12 }
        val e = assertFailsWith<ValidationException> { fences(user, tooMany) }
        assertEquals(listOf<Any>(2), e.args.toList())
        assertEquals(100_000, coins, "nothing charged on a refused line")
    }

    @Test
    fun `fences upgrade together and instantly up to the stadium's cap`() = runBlocking<Unit> {
        newClub()
        val ids = fences(user, listOf(11 to 19, 12 to 19, 13 to 19)).map { it.id }
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
        val office = get(user).buildings.first { it.type == BuildingType.TICKET_OFFICE }   // (9,9) 5x2
        val before = Atmosphere.of(get(user).buildings).getValue(office.id)
        fences(user, listOf(9 to 11, 10 to 11, 11 to 11, 9 to 12))
        val after = Atmosphere.of(get(user).buildings).getValue(office.id)
        assertEquals(4, after.fence)                                  // three touching, (0,2) one cell away
        assertEquals(before.total + 4, after.total)
    }

    @Test
    fun `edit mode swaps two buildings in one go and refuses overlaps`() = runBlocking<Unit> {
        newClub()
        val a = fences(user, listOf(9 to 20)).single(); val b = fences(user, listOf(10 to 20)).single()
        applyLayout(user, listOf(Placement(a.id, 10, 20, 0), Placement(b.id, 9, 20, 0)))
        val st = get(user)
        assertEquals(10, st.buildings.first { it.id == a.id }.gridX)
        assertEquals(9, st.buildings.first { it.id == b.id }.gridX)
        assertFailsWith<ConflictException> { applyLayout(user, listOf(Placement(a.id, 9, 20, 0))) }   // onto b
        val office = st.buildings.first { it.type == BuildingType.TICKET_OFFICE }
        assertFailsWith<ValidationException> { applyLayout(user, listOf(Placement(office.id, 29, 9, 0))) }   // 2 wide: outside the square
    }

    @Test
    fun `a saved layout brings the club back the way it was`() = runBlocking<Unit> {
        newClub()
        val f = fences(user, listOf(14 to 20)).single()
        layouts.save(user, 1, "Matchday")
        applyLayout(user, listOf(Placement(f.id, 18, 20, 0)))
        assertEquals(18, get(user).buildings.first { it.id == f.id }.gridX)
        layouts.applySlot(user, 1)
        assertEquals(14, get(user).buildings.first { it.id == f.id }.gridX)
        assertEquals(listOf("Matchday"), layouts.list(user).map { it.name })
        assertFailsWith<ValidationException> { layouts.save(user, 4, "x") }
        // a building put since on the saved spot makes the layout refuse, kindly
        val g = fences(user, listOf(18 to 20)).single()
        applyLayout(user, listOf(Placement(f.id, 19, 20, 0), Placement(g.id, 14, 20, 0)))
        assertFailsWith<ConflictException> { layouts.applySlot(user, 1) }
    }
}

class FakeLayoutRepository : com.matchdayclub.domain.repository.LayoutRepository {
    private val rows = mutableMapOf<Pair<UUID, Int>, LayoutSlot>()
    override suspend fun list(clubId: UUID) = rows.filterKeys { it.first == clubId }.values.sortedBy { it.slot }
    override suspend fun save(clubId: UUID, layout: LayoutSlot) { rows[clubId to layout.slot] = layout }
}
