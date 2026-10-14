package com.arenacity.domain.rules

import kotlin.test.*

class BuildingRulesTest {
    @Test
    fun `stadium has ten levels, buildings eight to ten, costs and requirements rise`() {
        assertEquals(10, BuildingCatalog.spec(BuildingType.PITCH).maxLevel)
        for (t in BuildingType.values().filter { it !in Stadium.COSMETIC }) {
            val s = BuildingCatalog.spec(t)
            assertTrue(s.maxLevel in (if (t == BuildingType.HALL_OF_FAME) 5..5 else 8..10), "$t has ${s.maxLevel} levels")
            assertTrue(s.levels.zipWithNext().all { (a, b) -> b.cost > a.cost && b.buildSeconds >= a.buildSeconds && b.requiredStadium >= a.requiredStadium && b.requiredTrophies >= a.requiredTrophies }, "$t curves")
            // every level is reachable: the cap at the top stadium is the max level, and caps never go down
            assertEquals(s.maxLevel, s.capAt(Stadium.MAX_LEVEL), "$t top cap")
            assertTrue(s.caps.zipWithNext().all { (a, b) -> b >= a } && s.counts.zipWithNext().all { (a, b) -> b >= a }, "$t caps/counts")
            // a level's stadium requirement is the first stadium whose cap allows it
            // (the tennis arena's are the old court's, for the courts V36 made into arenas: by its caps alone, below its count)
            if (t != BuildingType.PITCH) s.levels.forEachIndexed { i, l -> assertEquals((1..10).first { s.capAt(it) >= i + 1 && (s.countAt(it) > 0 || t == BuildingType.TENNIS_ARENA) }, l.requiredStadium, "$t L${i + 1}") }
        }
        assertEquals(0, BuildingCatalog.spec(BuildingType.PITCH).levels.first().cost)
        assertEquals(5_500, BuildingCatalog.level(BuildingType.PITCH, 10).requiredTrophies)
        assertEquals(3, BuildingCatalog.spec(BuildingType.BASKETBALL_ARENA).unlockStadium)
        assertEquals(2, BuildingCatalog.spec(BuildingType.SHOPPING_CENTER).unlockStadium)
    }

    /** The sport town's list (the owner, 14 October 2026): footprint, the stadium that opens it, copies at the top stadium. */
    @Test
    fun `the sport town's buildings follow the owner's list`() {
        data class Row(val w: Int, val h: Int, val unlock: Int, val top: Int)
        val list = mapOf(
            BuildingType.PITCH to Row(12, 9, 1, 1), BuildingType.ACADEMY to Row(7, 6, 1, 1), BuildingType.TRAINING_CENTER to Row(9, 6, 1, 1),
            BuildingType.MEDICAL_CENTER to Row(6, 6, 2, 1), BuildingType.TICKET_OFFICE to Row(7, 3, 1, 3), BuildingType.HOUSE to Row(4, 4, 1, 30),
            BuildingType.SHOPPING_CENTER to Row(7, 6, 2, 2), BuildingType.HALL_OF_FAME to Row(6, 7, 3, 1), BuildingType.BACKYARD_COURT to Row(4, 4, 3, 4),
            BuildingType.FAN_ZONE to Row(6, 6, 2, 1), BuildingType.MEDIA_CENTER to Row(6, 4, 6, 1), BuildingType.SPONSOR_OFFICE to Row(4, 4, 6, 1),
            BuildingType.HOTEL to Row(6, 6, 8, 1), BuildingType.ATHLETES_RESIDENCE to Row(7, 4, 2, 1), BuildingType.YOUTH_SPORTS_SCHOOL to Row(6, 6, 3, 1),
            BuildingType.SPORTS_SCIENCE_LAB to Row(6, 6, 4, 1), BuildingType.SCHOOL to Row(6, 6, 4, 1), BuildingType.TRANSPORT_HUB to Row(6, 4, 4, 1),
            BuildingType.SCOUTING_OFFICE to Row(4, 4, 5, 1), BuildingType.RECOVERY_CENTER to Row(6, 6, 5, 1), BuildingType.ANALYTICS_CENTER to Row(4, 4, 7, 1),
            BuildingType.COACHING_HQ to Row(4, 4, 8, 1), BuildingType.BASKETBALL_ARENA to Row(11, 8, 3, 1), BuildingType.VOLLEYBALL_ARENA to Row(11, 8, 5, 1),
            BuildingType.TENNIS_ARENA to Row(11, 8, 7, 1), BuildingType.PADEL_ARENA to Row(11, 8, 9, 1),
        )
        assertEquals(BuildingType.values().toSet() - Stadium.COSMETIC, list.keys)
        for ((t, r) in list) {
            val s = BuildingCatalog.spec(t)
            assertEquals(Row(r.w, r.h, r.unlock, r.top), Row(s.width, s.height, s.unlockStadium, s.maxPerClub), "$t")
        }
        assertTrue(BuildingCatalog.spec(BuildingType.HOUSE).countAt(1) in 1..3, "a few houses at the first stadium")
        // the new types have ten levels (the hall of fame keeps the museum's five); every level's art is the level-1 sprite for now
        for (t in list.keys - setOf(BuildingType.PITCH, BuildingType.ACADEMY, BuildingType.TRAINING_CENTER, BuildingType.MEDICAL_CENTER, BuildingType.HALL_OF_FAME))
            assertEquals(10, BuildingCatalog.spec(t).maxLevel, "$t")
        // the arenas stand on their islands; basketball and tennis open their sections, volleyball and padel are «soon»
        assertEquals(mapOf(BuildingType.BASKETBALL_ARENA to com.arenacity.domain.model.ZoneSport.BASKETBALL, BuildingType.VOLLEYBALL_ARENA to com.arenacity.domain.model.ZoneSport.VOLLEYBALL,
            BuildingType.TENNIS_ARENA to com.arenacity.domain.model.ZoneSport.TENNIS, BuildingType.PADEL_ARENA to com.arenacity.domain.model.ZoneSport.PADEL),
            BuildingCatalog.specs.values.filter { it.island != null }.associate { it.type to it.island })
        assertEquals(BuildingType.BASKETBALL_ARENA, Sections.venue(com.arenacity.domain.model.Sport.BASKETBALL))
        assertEquals(BuildingType.TENNIS_ARENA, Sections.venue(com.arenacity.domain.model.Sport.TENNIS))
        assertEquals(95, BuildingCatalog.level(BuildingType.BASKETBALL_ARENA, 8).effect, "the old court's levels")
        // an arena's island opens with the arena itself: never earlier (an island bought and left empty, its section
        // without a squad), never later; every island zone has its arena
        for (s in BuildingCatalog.specs.values.filter { it.island != null })
            assertEquals(s.unlockStadium, Zones.spec(s.island!!).requiredStadium, "${s.type}: its zone opens at ${Zones.spec(s.island!!).requiredStadium}")
        for (z in Lands.MAP.regions.mapNotNull { it.sport }) assertNotNull(Zones.arenaOf(z), "$z island's arena")
        // the ones with no mechanic yet: nothing to collect, no effect, «soon»
        val soon = setOf(BuildingType.ATHLETES_RESIDENCE, BuildingType.YOUTH_SPORTS_SCHOOL, BuildingType.SPORTS_SCIENCE_LAB, BuildingType.SCHOOL, BuildingType.TRANSPORT_HUB,
            BuildingType.SCOUTING_OFFICE, BuildingType.RECOVERY_CENTER, BuildingType.ANALYTICS_CENTER, BuildingType.COACHING_HQ, BuildingType.VOLLEYBALL_ARENA, BuildingType.PADEL_ARENA)
        assertEquals(soon, BuildingCatalog.specs.values.filter { it.soon }.map { it.type }.toSet())
        for (t in soon) assertTrue(BuildingCatalog.spec(t).levels.all { it.incomePerHour == 0L && it.effect == 0 && it.effect2 == 0 }, "$t has no effect yet")
        // the earners: the shopping centre pays the best of the old shop's, café's and car park's incomes, the fan zone the café's
        assertEquals(listOf(250L, 310, 390, 480, 600, 740, 920, 1_100, 1_400, 1_800), BuildingCatalog.spec(BuildingType.SHOPPING_CENTER).levels.map { it.incomePerHour })
        assertEquals(150, BuildingCatalog.level(BuildingType.FAN_ZONE, 1).incomePerHour)
        for (t in listOf(BuildingType.MEDIA_CENTER, BuildingType.SPONSOR_OFFICE, BuildingType.HOTEL)) assertTrue(BuildingCatalog.spec(t).levels.all { it.incomePerHour > 0 }, "$t earns")
    }

    /** The old tennis court (before the sport town): its caps by stadium; V36 made every court a TENNIS_ARENA at its level. */
    @Test
    fun `a court made into a tennis arena upgrades no later than the court did`() {
        val court = listOf(0, 0, 0, 2, 3, 4, 5, 6, 7, 8)
        val arena = BuildingCatalog.spec(BuildingType.TENNIS_ARENA)
        for (stadium in 1..10) for (level in 1..7) {
            if (court[stadium - 1] < level + 1) continue
            assertTrue(arena.capAt(stadium) >= level + 1, "L$level → L${level + 1} at stadium $stadium")
            assertTrue(BuildingCatalog.level(BuildingType.TENNIS_ARENA, level + 1).requiredStadium <= stadium, "L${level + 1} shown as needing a bigger stadium than $stadium")
        }
        assertEquals(listOf(60, 65, 70, 75, 80, 85, 90, 95), arena.levels.take(8).map { it.effect }, "the court's section caps")
        assertEquals(7, arena.unlockStadium, "a new club builds it from stadium 7")
        // the island: from stadium 7, or from the arena's first level's stadium for a club that already owns one
        assertEquals(7, Zones.requiredStadium(com.arenacity.domain.model.ZoneSport.TENNIS, emptySet()))
        assertEquals(4, Zones.requiredStadium(com.arenacity.domain.model.ZoneSport.TENNIS, setOf(BuildingType.TENNIS_ARENA)))
        assertEquals(3, Zones.requiredStadium(com.arenacity.domain.model.ZoneSport.BASKETBALL, setOf(BuildingType.BASKETBALL_ARENA)))
    }

    @Test
    fun `the stadium level ignores a stadium under construction, the plot grows with it`() {
        val now = java.time.Instant.parse("2026-10-10T00:00:00Z")
        fun pitch(level: Int, state: com.arenacity.domain.model.BuildingState) =
            com.arenacity.domain.model.Building(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), BuildingType.PITCH, level, 0, 0, 0, state, null, now, now, now)
        assertEquals(4, Stadium.level(listOf(pitch(4, com.arenacity.domain.model.BuildingState.IDLE))))
        assertEquals(3, Stadium.level(listOf(pitch(4, com.arenacity.domain.model.BuildingState.UPGRADING))))
        assertEquals(1, Stadium.level(emptyList()))
        assertEquals(48, Stadium.gridSize(1)); assertEquals(48, Stadium.gridSize(10))   // the plot never grows (the eras map; 48 cells since the archipelago v2)
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
        assertEquals(48, ClubLevels.gridSize(1)); assertEquals(48, ClubLevels.gridSize(10)); assertEquals(100, ClubLevels.reputationForNext(1)); assertNull(ClubLevels.reputationForNext(10))
    }

    @Test
    fun `footprints rotate and overlap`() {
        val a = footprint(BuildingType.PITCH, 0, 0, 0)   // 12x9
        assertEquals(Footprint(0, 0, 12, 9), a)
        assertEquals(Footprint(0, 0, 9, 12), footprint(BuildingType.PITCH, 0, 0, 90))
        assertTrue(a.overlaps(Footprint(11, 8, 2, 2))); assertFalse(a.overlaps(Footprint(12, 0, 2, 2)))
    }
}
