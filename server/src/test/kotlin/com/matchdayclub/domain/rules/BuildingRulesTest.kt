package com.matchdayclub.domain.rules

import kotlin.test.*

class BuildingRulesTest {
    @Test
    fun `stadium has ten levels, buildings eight to ten, costs and requirements rise`() {
        assertEquals(10, BuildingCatalog.spec(BuildingType.PITCH).maxLevel)
        for (t in BuildingType.values().filter { it !in Stadium.COSMETIC }) {
            val s = BuildingCatalog.spec(t)
            assertTrue(s.maxLevel in (if (t == BuildingType.MUSEUM) 5..5 else 8..10), "$t has ${s.maxLevel} levels")
            assertTrue(s.levels.zipWithNext().all { (a, b) -> b.cost > a.cost && b.buildSeconds >= a.buildSeconds && b.requiredStadium >= a.requiredStadium && b.requiredTrophies >= a.requiredTrophies }, "$t curves")
            // every level is reachable: the cap at the top stadium is the max level, and caps never go down
            assertEquals(s.maxLevel, s.capAt(Stadium.MAX_LEVEL), "$t top cap")
            assertTrue(s.caps.zipWithNext().all { (a, b) -> b >= a } && s.counts.zipWithNext().all { (a, b) -> b >= a }, "$t caps/counts")
            // a level's stadium requirement is the first stadium whose cap allows it
            if (t != BuildingType.PITCH) s.levels.forEachIndexed { i, l -> assertEquals((1..10).first { s.capAt(it) >= i + 1 && s.countAt(it) > 0 }, l.requiredStadium, "$t L${i + 1}") }
        }
        assertEquals(0, BuildingCatalog.spec(BuildingType.PITCH).levels.first().cost)
        assertEquals(5_500, BuildingCatalog.level(BuildingType.PITCH, 10).requiredTrophies)
        assertEquals(3, BuildingCatalog.spec(BuildingType.BASKETBALL_COURT).unlockStadium)
        assertEquals(2, BuildingCatalog.spec(BuildingType.CAFE).unlockStadium)
    }

    @Test
    fun `the stadium level ignores a stadium under construction, the plot grows with it`() {
        val now = java.time.Instant.parse("2026-10-10T00:00:00Z")
        fun pitch(level: Int, state: com.matchdayclub.domain.model.BuildingState) =
            com.matchdayclub.domain.model.Building(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), BuildingType.PITCH, level, 0, 0, 0, state, null, now, now, now)
        assertEquals(4, Stadium.level(listOf(pitch(4, com.matchdayclub.domain.model.BuildingState.IDLE))))
        assertEquals(3, Stadium.level(listOf(pitch(4, com.matchdayclub.domain.model.BuildingState.UPGRADING))))
        assertEquals(1, Stadium.level(emptyList()))
        assertEquals(30, Stadium.gridSize(1)); assertEquals(30, Stadium.gridSize(10))   // the whole square from day one
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
        assertEquals(30, ClubLevels.gridSize(1)); assertEquals(30, ClubLevels.gridSize(10)); assertEquals(100, ClubLevels.reputationForNext(1)); assertNull(ClubLevels.reputationForNext(10))
    }

    @Test
    fun `footprints rotate and overlap`() {
        val a = footprint(BuildingType.PITCH, 0, 0, 0)   // 8x6
        assertEquals(Footprint(0, 0, 8, 6), a)
        assertEquals(Footprint(0, 0, 6, 8), footprint(BuildingType.PITCH, 0, 0, 90))
        assertTrue(a.overlaps(Footprint(7, 5, 2, 2))); assertFalse(a.overlaps(Footprint(8, 0, 2, 2)))
        assertTrue(Footprint(Grid.lo(12), Grid.lo(12), 8, 6).fits(12)); assertFalse(a.fits(12)); assertFalse(Footprint(19, 9, 3, 1).fits(12))   // the grid is the centred 12 cells of the 30-cell land
    }
}
