package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.InsufficientFundsException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.BuildingState
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.util.UUID

class ClubUseCasesTest {
    private val clock = FixedClock()
    private val clubs = FakeClubRepository()
    private val buildings = FakeBuildingRepository()
    // the old square with a corner cut off: the cells these tests name are on it or off it whatever mask the server ships
    private val service = ClubService(clubs, buildings, clock, lands = TestLand.NOTCHED)
    private val create = CreateClubUseCase(clubs, buildings, clock, emptyList(), lands = TestLand.NOTCHED)
    private val get = GetClubUseCase(service)
    private val place = PlaceBuildingUseCase(service, buildings, clock)
    private val upgrade = UpgradeBuildingUseCase(service, buildings, clock)
    private val collect = CollectIncomeUseCase(service, clubs, buildings, clock)
    private val speed = SpeedUpUseCase(service, buildings, clock)
    private val update = UpdateClubUseCase(service, clubs, clock)
    private val user = UUID.randomUUID()

    @Test
    fun `update club validates and keeps unspecified fields`() = runBlocking<Unit> {
        newClub()
        val c = update(user, null, "#112233", null, """{"shape":"shield","icon":"ball"}""")
        assertEquals("Red Lions", c.name); assertEquals("#112233", c.primaryColor); assertEquals("#FFFFFF", c.secondaryColor); assertTrue(c.emblemJson!!.contains("shield"))
        assertFailsWith<ValidationException> { update(user, "ab", null, null, null) }
        assertFailsWith<ValidationException> { update(user, null, "red", null, null) }
        Unit
    }

    private fun newClub() = runBlocking { create(user, "Red Lions", "#AA0000", "#FFFFFF", "Europe/Kyiv") }

    @Test
    fun `create club seeds pitch and ticket office`() = runBlocking<Unit> {
        val c = newClub()
        val st = get(user)
        assertEquals(2_500, c.coins); assertEquals(2, st.buildings.size); assertEquals(48, st.gridSize)
        assertTrue(st.buildings.any { it.type == BuildingType.PITCH && it.state == BuildingState.IDLE })
        assertFailsWith<ConflictException> { create(user, "Again", "#000000", "#FFFFFF", "UTC") }
        assertFailsWith<ValidationException> { create(UUID.randomUUID(), "ab", "#000000", "#FFFFFF", "UTC") }
        Unit
    }

    @Test
    fun `place charges coins starts timer and finalises on read`() = runBlocking<Unit> {
        newClub()
        val b = place(user, BuildingType.ACADEMY, 8, 28, 0)
        assertEquals(BuildingState.UPGRADING, b.state); assertEquals(1_000, clubs.rows.values.first().coins)
        assertFailsWith<ConflictException> { collect(user, b.id) }
        clock.advanceSeconds(10 * 60)
        assertEquals(BuildingState.IDLE, get(user).buildings.first { it.id == b.id }.state)
    }

    @Test
    fun `placement validation`() = runBlocking<Unit> {
        newClub()
        assertFailsWith<ConflictException> { place(user, BuildingType.ACADEMY, 12, 12, 0) }           // overlaps pitch
        assertFailsWith<ValidationException> { place(user, BuildingType.ACADEMY, 40, 28, 0) }       // outside the plot (cells -9..38)
        assertFailsWith<ValidationException> { place(user, BuildingType.ACADEMY, -10, 0, 0) }
        assertFailsWith<ValidationException> { place(user, BuildingType.BACKYARD_COURT, 8, 28, 0) }  // stadium 3 required
        assertFailsWith<ValidationException> { place(user, BuildingType.SCHOOL, 8, 28, 0) }          // stadium 4 required
        assertFailsWith<ValidationException> { place(user, BuildingType.TICKET_OFFICE, 8, 28, 0) }  // a 2nd office needs stadium 4
        place(user, BuildingType.ACADEMY, 8, 28, 0)
        assertFailsWith<ConflictException> { place(user, BuildingType.ACADEMY, 18, 28, 0) }          // one academy, ever
        Unit
    }

    @Test
    fun `a house and the buildings with no mechanic yet are built and upgraded like any other`() = runBlocking<Unit> {
        newClub()
        clubs.update(clubs.rows.values.first().copy(coins = 1_000_000, gold = 500, trophies = 100_000))
        // houses from the first stadium (the sport town): a few, then more with every stadium
        place(user, BuildingType.HOUSE, 8, 28, 0).let { speed(user, it.id) }
        place(user, BuildingType.HOUSE, 16, 28, 0).let { speed(user, it.id) }
        assertEquals("Requires stadium level {0}", assertFailsWith<ValidationException> { place(user, BuildingType.HOUSE, 24, 28, 0) }.template)
        // stadium 2: the athletes' residence opens; it builds, earns nothing yet, and upgrades as the stadium grows
        val pitch = get(user).buildings.first { it.type == BuildingType.PITCH }
        upgrade(user, pitch.id).let { speed(user, it.id) }
        val residence = place(user, BuildingType.ATHLETES_RESIDENCE, 8, 32, 0).let { speed(user, it.id) }
        assertEquals("This building has no income", assertFailsWith<ValidationException> { collect(user, residence.id) }.template)
        assertEquals("Requires stadium level {0}", assertFailsWith<ValidationException> { upgrade(user, residence.id) }.template)
        upgrade(user, pitch.id).let { speed(user, it.id) }
        assertEquals(2, upgrade(user, residence.id).level)
        assertTrue(BuildingCatalog.spec(BuildingType.ATHLETES_RESIDENCE).soon)
    }

    @Test
    fun `collect income is capped and speed up costs gold`() = runBlocking<Unit> {
        newClub()
        val office = get(user).buildings.first { it.type == BuildingType.TICKET_OFFICE }
        clock.advanceSeconds(2 * 3600)
        assertEquals(264, collect(user, office.id).coins)   // 120/h +10 %: the office stands by the stadium
        clock.advanceSeconds(48 * 3600)
        assertEquals(792, collect(user, office.id).coins)
        assertEquals(2_500 + 264 + 792, clubs.rows.values.first().coins)
    }

    @Test
    fun `upgrades are capped by the stadium level`() = runBlocking<Unit> {
        newClub()
        val office = get(user).buildings.first { it.type == BuildingType.TICKET_OFFICE }
        clubs.update(clubs.rows.values.first().copy(coins = 100_000))
        assertFailsWith<ValidationException> { upgrade(user, office.id) }                    // office L2 needs stadium 2
        Unit
    }

    @Test
    fun `stadium upgrades need trophies and lift the caps once finished`() = runBlocking<Unit> {
        newClub()
        val office = get(user).buildings.first { it.type == BuildingType.TICKET_OFFICE }
        val pitch = get(user).buildings.first { it.type == BuildingType.PITCH }
        clubs.update(clubs.rows.values.first().copy(coins = 100_000))
        val up = upgrade(user, pitch.id)                                                         // stadium 2: 2 300 coins, 2 h, no trophies
        assertEquals(2, up.level); assertEquals(BuildingState.UPGRADING, up.state)
        assertFailsWith<ValidationException> { upgrade(user, office.id) }                    // still stadium 1 while it is built
        clock.advanceSeconds(2 * 3600)
        assertEquals(2, get(user).stadium)
        val o2 = upgrade(user, office.id)                                                        // office L2: 10 min
        assertEquals(2, o2.level)
        clock.advanceSeconds(60)
        val before = clubs.rows.values.first().gold
        val done = speed(user, office.id)
        assertEquals(BuildingState.IDLE, done.state); assertEquals(before - 1, clubs.rows.values.first().gold)   // 9 min left = 1 gold
        val coins = clubs.rows.values.first().coins
        clubs.update(clubs.rows.values.first().copy(coins = 0))
        assertFailsWith<InsufficientFundsException> { place(user, BuildingType.FAN_ZONE, 8, 28, 0) }  // the fan zone opens at stadium 2
        clubs.update(clubs.rows.values.first().copy(coins = coins))
        assertFailsWith<ValidationException> { upgrade(user, pitch.id) }                     // stadium 3 needs 300 trophies
        clubs.update(clubs.rows.values.first().copy(trophies = 300))
        assertEquals(3, upgrade(user, pitch.id).level)
        Unit
    }
}
