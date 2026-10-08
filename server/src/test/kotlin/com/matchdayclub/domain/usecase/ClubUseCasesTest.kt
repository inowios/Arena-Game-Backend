package com.matchdayclub.domain.usecase

import com.matchdayclub.domain.exception.ConflictException
import com.matchdayclub.domain.exception.InsufficientFundsException
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.BuildingState
import com.matchdayclub.domain.rules.BuildingType
import com.matchdayclub.domain.usecase.club.*
import com.matchdayclub.testutil.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.util.UUID

class ClubUseCasesTest {
    private val clock = FixedClock()
    private val clubs = FakeClubRepository()
    private val buildings = FakeBuildingRepository()
    private val service = ClubService(clubs, buildings, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, emptyList())
    private val get = GetClubUseCase(service)
    private val place = PlaceBuildingUseCase(service, buildings, clock)
    private val upgrade = UpgradeBuildingUseCase(service, buildings, clock)
    private val collect = CollectIncomeUseCase(service, clubs, buildings, clock)
    private val speed = SpeedUpUseCase(service, buildings, clock)
    private val user = UUID.randomUUID()

    private fun newClub() = runBlocking { create(user, "Red Lions", "#AA0000", "#FFFFFF", "Europe/Kyiv") }

    @Test
    fun `create club seeds pitch and ticket office`() = runBlocking {
        val c = newClub()
        val st = get(user)
        assertEquals(2_500, c.coins); assertEquals(2, st.buildings.size); assertEquals(12, st.gridSize)
        assertTrue(st.buildings.any { it.type == BuildingType.PITCH && it.state == BuildingState.IDLE })
        assertFailsWith<ConflictException> { create(user, "Again", "#000000", "#FFFFFF", "UTC") }
        assertFailsWith<ValidationException> { create(UUID.randomUUID(), "ab", "#000000", "#FFFFFF", "UTC") }
        Unit
    }

    @Test
    fun `place charges coins starts timer and finalises on read`() = runBlocking {
        newClub()
        val b = place(user, BuildingType.ACADEMY, 0, 10, 0)
        assertEquals(BuildingState.UPGRADING, b.state); assertEquals(2_000, clubs.rows.values.first().coins)
        assertFailsWith<ConflictException> { collect(user, b.id) }
        clock.advanceSeconds(10 * 60)
        assertEquals(BuildingState.IDLE, get(user).buildings.first { it.id == b.id }.state)
    }

    @Test
    fun `placement validation`() = runBlocking {
        newClub()
        assertFailsWith<ConflictException> { place(user, BuildingType.ACADEMY, 3, 4, 0) }           // overlaps pitch
        assertFailsWith<ValidationException> { place(user, BuildingType.ACADEMY, 11, 11, 0) }       // outside grid
        assertFailsWith<ValidationException> { place(user, BuildingType.PARKING, 0, 10, 0) }        // club level 3 required
        assertFailsWith<ValidationException> { place(user, BuildingType.TENNIS_COURT, 0, 10, 0) }   // disabled
        assertFailsWith<ConflictException> { place(user, BuildingType.TICKET_OFFICE, 0, 10, 0) }    // max 1
        Unit
    }

    @Test
    fun `collect income is capped and speed up costs gold`() = runBlocking {
        newClub()
        val office = get(user).buildings.first { it.type == BuildingType.TICKET_OFFICE }
        clock.advanceSeconds(2 * 3600)
        assertEquals(240, collect(user, office.id).coins)
        clock.advanceSeconds(48 * 3600)
        assertEquals(720, collect(user, office.id).coins)
        assertEquals(2_500 + 240 + 720, clubs.rows.values.first().coins)
    }

    @Test
    fun `upgrade requires club level and speed up works`() = runBlocking {
        newClub()
        val office = get(user).buildings.first { it.type == BuildingType.TICKET_OFFICE }
        assertFailsWith<ValidationException> { upgrade(user, office.id) }
        val club = clubs.rows.values.first()
        clubs.update(club.copy(level = 2, coins = 10_000))
        val up = upgrade(user, office.id)
        assertEquals(2, up.level); assertEquals(BuildingState.UPGRADING, up.state)
        clock.advanceSeconds(3600)
        val before = clubs.rows.values.first().gold
        val done = speed(user, office.id)
        assertEquals(BuildingState.IDLE, done.state); assertEquals(before - 12, clubs.rows.values.first().gold)   // 2h left = 12 gold
        clubs.update(clubs.rows.values.first().copy(coins = 0))
        assertFailsWith<InsufficientFundsException> { place(user, BuildingType.CAFE, 0, 10, 0) }
        Unit
    }
}
