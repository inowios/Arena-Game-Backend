package com.arenacity

import com.arenacity.domain.rules.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The showcase cities stand on the plot alone since the archipelago (land is not for sale): a planned town on 48×48 cells. */
class ShowcaseTest {
    private fun check(stadium: Int) {
        val city = Showcase.city(stadium)
        assertEquals(Land.HOME, city.parcels, "the plot only")
        val lo = Grid.lo(Grid.PLOT); val hi = Grid.hi(Grid.PLOT)
        val cells = HashMap<Pair<Int, Int>, BuildingType>()
        for (s in city.slots) {
            val spec = BuildingCatalog.spec(s.type)
            assertTrue(s.level in 1..spec.capAt(stadium).coerceAtLeast(1), "${s.type} level ${s.level} over the cap at stadium $stadium")
            assertTrue(Land.fits(Footprint(s.x, s.y, spec.width, spec.height), Land.HOME), "${s.type} at ${s.x},${s.y} is off the plot")
            for (x in s.x until s.x + spec.width) for (y in s.y until s.y + spec.height) {
                assertTrue(x in lo until hi && y in lo until hi, "${s.type} at $x,$y is off the plot")
                assertTrue(cells.put(x to y, s.type) == null, "${s.type} overlaps ${cells[x to y]} at $x,$y")
            }
        }
        val counts = city.slots.groupingBy { it.type }.eachCount()
        for ((type, n) in counts) assertTrue(n <= BuildingCatalog.spec(type).countAt(stadium), "$type: $n > ${BuildingCatalog.spec(type).countAt(stadium)}")
        // the stadium in the middle of the plot, at the showcase's level
        val pitch = city.slots.single { it.type == BuildingType.PITCH }
        assertEquals(stadium, pitch.level)
        val ps = BuildingCatalog.spec(BuildingType.PITCH)
        assertEquals(Grid.CENTRE * 2, pitch.x * 2 + ps.width); assertEquals(Grid.CENTRE * 2, pitch.y * 2 + ps.height)
        // nothing touches the stadium, and no two buildings share an edge (each stands on its own lawn)
        val solid = city.slots.filter { it.type != BuildingType.ROAD && it.type != BuildingType.DECOR }
        for (a in solid) for (b in solid) if (a !== b) {
            val sa = BuildingCatalog.spec(a.type); val sb = BuildingCatalog.spec(b.type)
            val gapX = maxOf(b.x - (a.x + sa.width), a.x - (b.x + sb.width)); val gapY = maxOf(b.y - (a.y + sa.height), a.y - (b.y + sb.height))
            assertTrue(maxOf(gapX, gapY) >= 1, "${a.type} at ${a.x},${a.y} touches ${b.type} at ${b.x},${b.y}")
        }
        assertEquals(stadium, Stadium.level(city.slots.map { com.arenacity.domain.model.Building(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), it.type, it.level, it.x, it.y, 0,
            com.arenacity.domain.model.BuildingState.IDLE, null, java.time.Instant.EPOCH, java.time.Instant.EPOCH, java.time.Instant.EPOCH) }))
        // every type the stadium allows is there at least once, the club's buildings every copy (the town as many as fit spaciously)
        for (type in BuildingType.values()) {
            if (type == BuildingType.FENCE || BuildingCatalog.spec(type).countAt(stadium) == 0) continue
            assertTrue((counts[type] ?: 0) >= 1, "$type at stadium $stadium")
            if (type !in setOf(BuildingType.HOUSE, BuildingType.APARTMENT, BuildingType.OFFICE, BuildingType.PARK, BuildingType.ROAD, BuildingType.DECOR))
                assertEquals(BuildingCatalog.spec(type).countAt(stadium), counts[type], "every $type at stadium $stadium")
        }
        // the ring round the plaza at every stadium, the whole grid (boulevards and avenues out to the plot's edges) at the top
        val roads = city.slots.filter { it.type == BuildingType.ROAD }.map { it.x to it.y }.toSet()
        for (i in 2..27) assertTrue((2 to i) in roads && (27 to i) in roads && (i to 2) in roads && (i to 27) in roads, "the ring at $i, stadium $stadium")
        if (stadium >= 8) for (i in lo until hi) {
            for (a in listOf(2, 27)) assertTrue((a to i) in roads && (i to a) in roads, "the avenues at $a/$i, stadium $stadium")
            // the boulevards on the axes, from the plot's edges to the stadium's lawn
            if (i !in 10..19) for (a in listOf(14, 15)) assertTrue((a to i) in roads && (i to a) in roads, "the boulevards at $a/$i, stadium $stadium")
        }
        // every tree the stadium allows stands on a kerb or the plot's edge
        assertEquals(BuildingCatalog.spec(BuildingType.DECOR).countAt(stadium), counts[BuildingType.DECOR] ?: 0, "trees at stadium $stadium")
        // a pavement cell between every building and the streets (the street trees stand there)
        for (s in solid) { val sp = BuildingCatalog.spec(s.type); for (x in s.x until s.x + sp.width) for (y in s.y until s.y + sp.height)
            for (n in listOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1)) assertTrue(n !in roads, "${s.type} at ${s.x},${s.y} fronts the street at $n") }
        // the twelve blocks round the plaza (11×11 between the grid's lines): at the top every one is built, a whole town;
        // at stadium 5 the town hugs the plaza and the corner blocks are still meadow, room to grow
        val spans = listOf(lo..1, 3..13, 16..26, 28 until hi)
        for (i in 0..3) for (j in 0..3) if (i !in 1..2 || j !in 1..2) {
            val n = solid.count { it.x in spans[i] && it.y in spans[j] }
            val corner = i % 3 == 0 && j % 3 == 0
            if (stadium == Stadium.MAX_LEVEL) assertTrue(n >= 2, "block $i,$j holds $n buildings at the top")
            if (stadium == 5) assertTrue(if (corner) n == 0 else n >= 1, "block $i,$j holds $n buildings at stadium 5")
        }
        val built = solid.sumOf { BuildingCatalog.spec(it.type).width * BuildingCatalog.spec(it.type).height }
        assertTrue(built * 100 / (Grid.PLOT * Grid.PLOT) in (if (stadium == Stadium.MAX_LEVEL) 25..45 else 10..30), "$built cells built at stadium $stadium")
        if (System.getenv("SHOWCASE_MAP") != null) {
            val ch = mapOf(BuildingType.ROAD to '#', BuildingType.DECOR to '*', BuildingType.PITCH to 'P', BuildingType.HOUSE to 'h', BuildingType.APARTMENT to 'a',
                BuildingType.OFFICE to 'o', BuildingType.PARK to 'p', BuildingType.MEDICAL_CENTER to 'm', BuildingType.TRAINING_CENTER to 'R', BuildingType.TENNIS_COURT to 'N',
                BuildingType.CLUB_SHOP to 'S', BuildingType.CAFE to 'c', BuildingType.PARKING to 'K')
            println("stadium $stadium: " + counts.entries.sortedBy { it.key.ordinal }.joinToString { "${it.key}=${it.value}" } + ", built $built cells (${built * 100 / (Grid.PLOT * Grid.PLOT)} %)")
            for (y in lo until hi) println((lo until hi).joinToString("") { x -> cells[x to y]?.let { (ch[it] ?: it.name.first()).toString() } ?: "." })
        }
    }

    @Test fun `stadium 5 showcase fits the plot`() = check(5)
    @Test fun `top showcase fits the plot`() = check(10)

    @Test
    fun `an older showcase gives its land back and is rebuilt on the plot`() = kotlinx.coroutines.runBlocking {
        val clubs = com.arenacity.testutil.FakeClubRepository(); val buildings = com.arenacity.testutil.FakeBuildingRepository()
        val land = com.arenacity.domain.usecase.FakeLandRepository()
        val service = com.arenacity.domain.usecase.social.ShowcaseService(com.arenacity.testutil.FakeUserRepository(), clubs, buildings, land, com.arenacity.testutil.FixedClock())
        service.ensure()
        val ids = com.arenacity.domain.usecase.social.ShowcaseService.DEFS.map { it.id }
        // the layout before the archipelago owned the whole field
        for (id in ids) for (c in 0 until Land.PARCELS) for (r in 0 until Land.PARCELS) if ((c to r) !in Land.HOME) land.add(id, c, r, java.time.Instant.EPOCH)
        service.ensure()
        for ((id, d) in ids.zip(com.arenacity.domain.usecase.social.ShowcaseService.DEFS)) {
            assertEquals(emptyList(), land.listByClub(id), "${d.name} keeps no land round the plot")
            assertEquals(Showcase.city(d.stadium).slots.size, buildings.listByClub(id).size)
        }
    }
}
