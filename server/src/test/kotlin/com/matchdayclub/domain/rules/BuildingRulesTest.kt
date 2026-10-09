package com.matchdayclub.domain.rules

import kotlin.test.*

class BuildingRulesTest {
    @Test
    fun `catalog has three levels for core buildings and rising costs`() {
        for (t in listOf(BuildingType.PITCH, BuildingType.ACADEMY, BuildingType.TICKET_OFFICE)) {
            val s = BuildingCatalog.spec(t)
            assertEquals(3, s.maxLevel)
            assertTrue(s.levels.zipWithNext().all { (a, b) -> b.cost > a.cost && b.requiredClubLevel >= a.requiredClubLevel })
        }
        assertTrue(BuildingCatalog.spec(BuildingType.TENNIS_COURT).enabled)
    }

    @Test
    fun `income is capped and scaled by club level`() {
        val spec = BuildingCatalog.level(BuildingType.TICKET_OFFICE, 1)   // 120/h cap 6h
        assertEquals(120, BuildingCatalog.income(spec, 3600, 1))
        assertEquals(720, BuildingCatalog.income(spec, 20 * 3600, 1))
        assertEquals(126, BuildingCatalog.income(spec, 3600, 2))
        assertEquals(0, BuildingCatalog.income(BuildingCatalog.level(BuildingType.PITCH, 1), 3600, 1))
    }

    @Test
    fun `speed up gold`() {
        assertEquals(0, BuildingCatalog.speedUpGold(0))
        assertEquals(1, BuildingCatalog.speedUpGold(1))
        assertEquals(1, BuildingCatalog.speedUpGold(600))
        assertEquals(2, BuildingCatalog.speedUpGold(601))
        assertEquals(12, BuildingCatalog.speedUpGold(7200))
    }

    @Test
    fun `club levels and grid`() {
        assertEquals(1, ClubLevels.levelFor(0)); assertEquals(1, ClubLevels.levelFor(99)); assertEquals(2, ClubLevels.levelFor(100)); assertEquals(10, ClubLevels.levelFor(99_999))
        assertEquals(12, ClubLevels.gridSize(1)); assertEquals(30, ClubLevels.gridSize(10)); assertEquals(100, ClubLevels.reputationForNext(1)); assertNull(ClubLevels.reputationForNext(10))
    }

    @Test
    fun `footprints rotate and overlap`() {
        val a = footprint(BuildingType.PITCH, 0, 0, 0)   // 8x6
        assertEquals(Footprint(0, 0, 8, 6), a)
        assertEquals(Footprint(0, 0, 6, 8), footprint(BuildingType.PITCH, 0, 0, 90))
        assertTrue(a.overlaps(Footprint(7, 5, 2, 2))); assertFalse(a.overlaps(Footprint(8, 0, 2, 2)))
        assertTrue(a.fits(12)); assertFalse(Footprint(10, 0, 3, 1).fits(12))
    }
}
