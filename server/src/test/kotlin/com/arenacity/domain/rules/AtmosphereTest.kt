package com.arenacity.domain.rules

import com.arenacity.domain.model.Building
import com.arenacity.domain.model.BuildingState
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class AtmosphereTest {
    private val now = Instant.parse("2026-10-10T00:00:00Z")
    private val club = UUID.randomUUID()
    private fun b(type: BuildingType, x: Int, y: Int) = Building(UUID.randomUUID(), club, type, 1, x, y, 0, BuildingState.IDLE, null, now, now, now)

    @Test
    fun `gap between footprints`() {
        assertEquals(0, Atmosphere.gap(Footprint(0, 0, 2, 2), Footprint(2, 0, 1, 1)))
        assertEquals(1, Atmosphere.gap(Footprint(0, 0, 2, 2), Footprint(3, 0, 1, 1)))
        assertEquals(3, Atmosphere.gap(Footprint(0, 0, 2, 2), Footprint(1, 5, 1, 1)))
    }

    @Test
    fun `decor, the stadium and a yard court lift income, capped`() {
        val pitch = b(BuildingType.PITCH, 0, 0)              // 8×6
        val fans = b(BuildingType.FAN_ZONE, 9, 0)             // 4×4, gap 1 to the pitch
        val far = b(BuildingType.SHOPPING_CENTER, 20, 20)
        val decor = (14..15).flatMap { x -> (0..4).map { y -> b(BuildingType.DECOR, x, y) } }   // ten trees beside the fan zone
        val court = b(BuildingType.BACKYARD_COURT, 13, 5)    // 3×3, gap 1 below it
        val bonus = Atmosphere.of(listOf(pitch, fans, far, court) + decor)
        val c = bonus.getValue(fans.id)
        assertEquals(Atmosphere.DECOR_CAP, c.decor)            // decor part capped
        assertEquals(Atmosphere.STADIUM_PCT, c.stadium)
        assertEquals(BuildingCatalog.level(BuildingType.BACKYARD_COURT, 1).effect2, c.court)
        assertEquals(0, c.parking)                              // the car park went with the sport town
        assertEquals(37, c.total)
        assertEquals(Atmosphere.CAP, Atmosphere.Bonus(30, 10, 0, 8, court = 5).total)   // never above the cap
        assertEquals(0, bonus.getValue(far.id).total)
        assertEquals(0, bonus.getValue(pitch.id).total)        // not an income building
        assertEquals(0, bonus.getValue(court.id).total)        // nor is the yard court
        // a yard court still being built lifts nothing yet; the hotel, the press and the sponsors earn like the shops
        val building = court.copy(state = BuildingState.UPGRADING)
        assertEquals(0, Atmosphere.of(listOf(pitch, fans, building)).getValue(fans.id).court)
        val hotel = b(BuildingType.HOTEL, 13, 9)
        assertEquals(BuildingCatalog.level(BuildingType.BACKYARD_COURT, 1).effect2, Atmosphere.of(listOf(court, hotel)).getValue(hotel.id).court)
        val byTheStands = hotel.copy(gridX = 9, gridY = 0)
        assertEquals(0, Atmosphere.of(listOf(pitch, byTheStands)).getValue(byTheStands.id).stadium)   // the hotel does not live off the crowd
    }

    @Test
    fun `income applies the bonus`() {
        val spec = BuildingCatalog.level(BuildingType.TICKET_OFFICE, 1)   // 120/h
        assertEquals(120, BuildingCatalog.income(spec, 3600, 1))
        assertEquals(132, BuildingCatalog.income(spec, 3600, 1, 10))
    }
}
