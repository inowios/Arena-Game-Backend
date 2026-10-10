package com.matchdayclub.domain.rules

import com.matchdayclub.domain.model.Building
import com.matchdayclub.domain.model.BuildingState
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
    fun `decor, the stadium and a car park lift income, capped`() {
        val pitch = b(BuildingType.PITCH, 0, 0)              // 8×6
        val cafe = b(BuildingType.CAFE, 9, 0)                 // gap 1 to the pitch
        val far = b(BuildingType.CLUB_SHOP, 20, 20)
        val decor = (11..12).flatMap { x -> (0..4).map { y -> b(BuildingType.DECOR, x, y) } }   // ten trees beside the café
        val parking = b(BuildingType.PARKING, 12, 4)
        val bonus = Atmosphere.of(listOf(pitch, cafe, far, parking) + decor)
        val c = bonus.getValue(cafe.id)
        assertEquals(Atmosphere.DECOR_CAP, c.decor)            // decor part capped
        assertEquals(Atmosphere.STADIUM_PCT, c.stadium)
        assertEquals(Atmosphere.PARKING_PCT, c.parking)
        assertEquals(39, c.total)
        assertEquals(Atmosphere.CAP, Atmosphere.Bonus(30, 10, 5, 8).total)   // never above the cap
        assertEquals(0, bonus.getValue(far.id).total)
        assertEquals(0, bonus.getValue(pitch.id).total)        // not an income building
        assertEquals(0, bonus.getValue(parking.id).stadium)     // the car park does not live off the crowd
    }

    @Test
    fun `income applies the bonus`() {
        val spec = BuildingCatalog.level(BuildingType.TICKET_OFFICE, 1)   // 120/h
        assertEquals(120, BuildingCatalog.income(spec, 3600, 1))
        assertEquals(132, BuildingCatalog.income(spec, 3600, 1, 10))
    }
}
