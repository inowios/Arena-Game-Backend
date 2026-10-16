package com.arenacity.domain.rules

import kotlin.test.*

class BuildingRulesTest {
    @Test
    fun `stadium has ten levels, buildings eight to ten, costs and requirements rise`() {
        assertEquals(10, BuildingCatalog.spec(BuildingType.PITCH).maxLevel)
        for (t in BuildingType.values().filter { it !in Stadium.COSMETIC }) {
            val s = BuildingCatalog.spec(t)
            assertEquals(10, s.maxLevel, "$t has ${s.maxLevel} levels")   // every building ten levels since 16 October
            assertTrue(s.levels.zipWithNext().all { (a, b) -> b.cost > a.cost && b.buildSeconds >= a.buildSeconds && b.requiredStadium >= a.requiredStadium && b.requiredTrophies >= a.requiredTrophies }, "$t curves")
            // every level is reachable: the cap at the top stadium is the max level, and caps never go down
            assertEquals(s.maxLevel, s.capAt(Stadium.MAX_LEVEL), "$t top cap")
            assertTrue(s.caps.zipWithNext().all { (a, b) -> b >= a } && s.counts.zipWithNext().all { (a, b) -> b >= a }, "$t caps/counts")
            // a level's stadium requirement is the first stadium whose cap allows it
            if (t != BuildingType.PITCH) s.levels.forEachIndexed { i, l -> assertEquals((1..10).first { s.capAt(it) >= i + 1 && s.countAt(it) > 0 }, l.requiredStadium, "$t L${i + 1}") }
        }
        assertEquals(0, BuildingCatalog.spec(BuildingType.PITCH).levels.first().cost)
        assertEquals(5_500, BuildingCatalog.level(BuildingType.PITCH, 10).requiredTrophies)
        assertEquals(2, BuildingCatalog.spec(BuildingType.SHOPPING_CENTER).unlockStadium)
    }

    /**
     * The whole town fits (the review of 14 October, pass 3): at every stadium level every main-island type at its count,
     * the decor, the fences and the roads at their allowance take at most 80 % of the main island's whole tiles (the
     * shipped land mask), so a full town leaves room for lawns and odd corners.
     */
    @Test
    fun `a full town at every stadium level fits the main island`() {
        val main = Lands.MAP.main
        val tiles = main.tiles().cells.size * Grid.TILE * Grid.TILE
        for (stadium in 1..Stadium.MAX_LEVEL) {
            val cells = BuildingType.values().map { BuildingCatalog.spec(it) }.filter { it.enabled }
                .sumOf { it.countAt(stadium) * it.width * it.height }
            assertTrue(cells <= 0.8 * tiles, "stadium $stadium: $cells of $tiles cells")
        }
    }

    /** The sport town's list (the owner, 14 October 2026): footprint, the stadium that opens it, copies at the top stadium. */
    @Test
    fun `the sport town's buildings follow the owner's list`() {
        data class Row(val w: Int, val h: Int, val unlock: Int, val top: Int)
        // the footprints from each building's own drawing on the build grid's 20 m tiles (14 October, pass 3): whole tiles
        // 16 October: the big buildings a tile or two bigger each way (the owner: far too small beside the 16×16 stadium),
        // the houses 10 at the top, the ticket offices 2, the yard courts 2; the recovery centre and the coaching HQ merged away
        val list = mapOf(
            BuildingType.PITCH to Row(16, 16, 1, 1), BuildingType.ACADEMY to Row(10, 6, 1, 1), BuildingType.TRAINING_CENTER to Row(12, 8, 1, 1),
            BuildingType.MEDICAL_CENTER to Row(8, 6, 2, 1), BuildingType.TICKET_OFFICE to Row(6, 6, 1, 2), BuildingType.HOUSE to Row(6, 4, 1, 10),
            BuildingType.SHOPPING_CENTER to Row(8, 8, 2, 2), BuildingType.HALL_OF_FAME to Row(8, 8, 3, 1), BuildingType.BACKYARD_COURT to Row(4, 4, 3, 2),
            BuildingType.FAN_ZONE to Row(8, 8, 2, 1), BuildingType.MEDIA_CENTER to Row(8, 6, 6, 1), BuildingType.SPONSOR_OFFICE to Row(6, 6, 6, 1),
            BuildingType.HOTEL to Row(8, 6, 8, 1), BuildingType.ATHLETES_RESIDENCE to Row(8, 6, 2, 1), BuildingType.YOUTH_SPORTS_SCHOOL to Row(8, 6, 3, 1),
            BuildingType.SPORTS_SCIENCE_LAB to Row(8, 6, 4, 1), BuildingType.SCHOOL to Row(6, 6, 3, 1), BuildingType.TRANSPORT_HUB to Row(4, 4, 4, 1),
            BuildingType.SCOUTING_OFFICE to Row(6, 6, 2, 1), BuildingType.ANALYTICS_CENTER to Row(8, 6, 3, 1),
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
        // no island arenas any more (15 October 2026): football is the one section, the stadium its venue
        assertEquals(listOf(com.arenacity.domain.model.Sport.FOOTBALL), Sections.ORDER)
        assertEquals(BuildingType.PITCH, Sections.venue(com.arenacity.domain.model.Sport.FOOTBALL))
        // the ones with no mechanic yet: nothing to collect, no effect, «soon»
        // the academy waits for the cards' skills; the youth school's graduates and the scouting office's reports work (16 October)
        val soon = setOf(BuildingType.ACADEMY, BuildingType.ATHLETES_RESIDENCE, BuildingType.SPORTS_SCIENCE_LAB, BuildingType.SCHOOL, BuildingType.TRANSPORT_HUB,
            BuildingType.ANALYTICS_CENTER)
        assertEquals(soon, BuildingCatalog.specs.values.filter { it.soon }.map { it.type }.toSet())
        for (t in soon) assertTrue(BuildingCatalog.spec(t).levels.all { it.incomePerHour == 0L && it.effect == 0 && it.effect2 == 0 }, "$t has no effect yet")
        // the earners: the shopping centre pays the best of the old shop's, café's and car park's incomes, the fan zone the café's
        assertEquals(listOf(250L, 310, 390, 480, 600, 740, 920, 1_100, 1_400, 1_800), BuildingCatalog.spec(BuildingType.SHOPPING_CENTER).levels.map { it.incomePerHour })
        assertEquals(150, BuildingCatalog.level(BuildingType.FAN_ZONE, 1).incomePerHour)
        for (t in listOf(BuildingType.MEDIA_CENTER, BuildingType.SPONSOR_OFFICE, BuildingType.HOTEL)) assertTrue(BuildingCatalog.spec(t).levels.all { it.incomePerHour > 0 }, "$t earns")
    }

    /**
     * The bridges to the peoples' islands (the owner, 15 October 2026): each island in the shipped mask is a people's, its
     * bridge needs the stadium of the people's era (the beastfolk 4, the stone people 5, the androids 7, the shadows 10)
     * and keeps the price and the building time the island's zone had.
     */
    @Test
    fun `every island is a people's, its bridge at the people's era`() {
        assertEquals(com.arenacity.domain.model.People.entries.toSet(), Lands.MAP.regions.mapNotNull { it.people }.toSet())
        assertEquals(mapOf("beast" to com.arenacity.domain.model.People.BEAST, "stone" to com.arenacity.domain.model.People.STONE, "android" to com.arenacity.domain.model.People.ANDROID, "shadow" to com.arenacity.domain.model.People.SHADOW),
            Lands.MAP.regions.filter { it.people != null }.associate { it.id to it.people })
        assertEquals(mapOf(com.arenacity.domain.model.People.BEAST to 4, com.arenacity.domain.model.People.STONE to 5, com.arenacity.domain.model.People.ANDROID to 7, com.arenacity.domain.model.People.SHADOW to 10), Zones.specs.mapValues { it.value.requiredStadium })
        assertEquals(mapOf(com.arenacity.domain.model.People.BEAST to 8_000L, com.arenacity.domain.model.People.STONE to 50_000L, com.arenacity.domain.model.People.ANDROID to 40_000L, com.arenacity.domain.model.People.SHADOW to 120_000L), Zones.specs.mapValues { it.value.priceCoins })
        // the era of each people is the one its bridge's stadium starts (stadium 1–3 / 4–6 / 7–9 / 10)
        for ((p, z) in Zones.specs) assertEquals(Peoples.era(p), when (z.requiredStadium) { in 1..3 -> 1; in 4..6 -> 2; in 7..9 -> 3; else -> 4 }, "$p")
        // the old sport names still read (an old client, an old mask): the island each sport's arena stood on
        assertEquals(com.arenacity.domain.model.People.BEAST, com.arenacity.domain.model.People.parse("BASKETBALL")); assertEquals(com.arenacity.domain.model.People.STONE, com.arenacity.domain.model.People.parse("volleyball")); assertEquals(com.arenacity.domain.model.People.ANDROID, com.arenacity.domain.model.People.parse("TENNIS"))
        assertEquals(com.arenacity.domain.model.People.SHADOW, com.arenacity.domain.model.People.parse("PADEL")); assertEquals(com.arenacity.domain.model.People.SHADOW, com.arenacity.domain.model.People.parse("shadow")); assertNull(com.arenacity.domain.model.People.parse("HOCKEY"))
        // the packs: humans always, a people only once its island is open
        assertTrue(Peoples.dropsInPacks(null, emptySet())); assertFalse(Peoples.dropsInPacks(com.arenacity.domain.model.People.ANDROID, setOf(com.arenacity.domain.model.People.BEAST)))
        assertTrue(Peoples.dropsInPacks(com.arenacity.domain.model.People.BEAST, setOf(com.arenacity.domain.model.People.BEAST)))
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
        val a = footprint(BuildingType.PITCH, 0, 0, 0)   // 16x16
        assertEquals(Footprint(0, 0, 16, 16), a)
        assertEquals(Footprint(0, 0, 8, 12), footprint(BuildingType.TRAINING_CENTER, 0, 0, 90))   // 12x8 turned (the academy is square since 16 October)
        assertTrue(a.overlaps(Footprint(14, 14, 2, 2))); assertFalse(a.overlaps(Footprint(16, 0, 2, 2)))
        // every footprint is whole tiles of the build grid (14 October, pass 3), DECOR, FENCE and ROAD one tile each
        for (t in BuildingType.values()) { val s = BuildingCatalog.spec(t); assertTrue(s.width % Grid.TILE == 0 && s.height % Grid.TILE == 0, "$t ${s.width}x${s.height}") }
        for (t in Stadium.COSMETIC) assertEquals(Grid.TILE to Grid.TILE, BuildingCatalog.spec(t).let { it.width to it.height }, "$t")
        assertTrue(Grid.aligned(0, -4) && Grid.aligned(-10_000, -10_010)); assertFalse(Grid.aligned(1, 0)); assertFalse(Grid.aligned(0, -3))
    }
}
