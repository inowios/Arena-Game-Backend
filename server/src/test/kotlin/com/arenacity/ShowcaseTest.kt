package com.arenacity

import com.arenacity.domain.rules.*
import com.arenacity.testutil.TestLand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The showcase cities stand on the land mask's main island (an organic meadow since the owner's picture): a planned town
 * that fits whatever shape the mask has — checked on the shipped mask, on an organic one cut from the picture and on the
 * old square with a corner cut off.
 */
class ShowcaseTest {
    private fun check(stadium: Int, lands: LandMap) {
        val city = Showcase.city(stadium, lands)
        val land = lands.main
        // the main island and the side islands whose zone the stadium allows
        val open = Showcase.islands(stadium, lands)
        assertEquals(setOf(land.id) + open.map { it.id }, city.regions, "the main island and the open side islands")
        assertEquals(open.mapNotNull { it.sport }.toSet(), city.zones)
        val ground = LandSet(listOf(land) + open)
        val cells = HashMap<Pair<Int, Int>, BuildingType>()
        for (s in city.slots) {
            val spec = BuildingCatalog.spec(s.type)
            assertTrue(s.level in 1..spec.capAt(stadium).coerceAtLeast(1), "${s.type} level ${s.level} over the cap at stadium $stadium")
            // an arena on its own open island, everything else on the main island
            if (spec.island != null) assertTrue(lands.allows(ground, s.type, Footprint(s.x, s.y, spec.width, spec.height)), "${s.type} at ${s.x},${s.y} is off its island")
            else assertTrue(land.fits(Footprint(s.x, s.y, spec.width, spec.height)), "${s.type} at ${s.x},${s.y} is off the island")
            for (x in s.x until s.x + spec.width) for (y in s.y until s.y + spec.height)
                assertTrue(cells.put(x to y, s.type) == null, "${s.type} overlaps ${cells[x to y]} at $x,$y")
        }
        // SHOWCASE_MAP=1: print the town (the island's cells as dots)
        if (System.getenv("SHOWCASE_MAP") != null) {
            val ch = mapOf(BuildingType.ROAD to '#', BuildingType.DECOR to '*', BuildingType.PITCH to 'P', BuildingType.HOUSE to 'h', BuildingType.ACADEMY to 'A',
                BuildingType.TRAINING_CENTER to 'R', BuildingType.MEDICAL_CENTER to 'm', BuildingType.TICKET_OFFICE to 'T', BuildingType.SHOPPING_CENTER to 'S',
                BuildingType.HALL_OF_FAME to 'F', BuildingType.BACKYARD_COURT to 'b', BuildingType.FAN_ZONE to 'z', BuildingType.MEDIA_CENTER to 'M',
                BuildingType.SPONSOR_OFFICE to 'o', BuildingType.HOTEL to 'H', BuildingType.ATHLETES_RESIDENCE to 'r', BuildingType.YOUTH_SPORTS_SCHOOL to 'y',
                BuildingType.SPORTS_SCIENCE_LAB to 'l', BuildingType.SCHOOL to 's', BuildingType.TRANSPORT_HUB to 't', BuildingType.SCOUTING_OFFICE to 'c',
                BuildingType.RECOVERY_CENTER to 'v', BuildingType.ANALYTICS_CENTER to 'a', BuildingType.COACHING_HQ to 'q')
            val built = city.slots.filter { it.type != BuildingType.ROAD && it.type != BuildingType.DECOR && BuildingCatalog.spec(it.type).island == null }
                .sumOf { BuildingCatalog.spec(it.type).width * BuildingCatalog.spec(it.type).height }
            println("stadium $stadium on ${land.area} cells: " + city.slots.groupingBy { it.type }.eachCount().entries.sortedBy { it.key.ordinal }.joinToString { "${it.key}=${it.value}" } + ", built $built cells (${built * 100 / land.area} %)")
            for (y in land.y0 until land.y0 + land.h) println((land.x0 until land.x0 + land.w).joinToString("") { x -> cells[x to y]?.let { (ch[it] ?: it.name.first()).toString() } ?: if (land.has(x, y)) "." else " " })
            for (s in city.slots.filter { BuildingCatalog.spec(it.type).island != null }) println("${s.type} L${s.level} at ${s.x},${s.y}")
        }
        val counts = city.slots.groupingBy { it.type }.eachCount()
        for ((type, n) in counts) assertTrue(n <= BuildingCatalog.spec(type).countAt(stadium), "$type: $n > ${BuildingCatalog.spec(type).countAt(stadium)}")
        // the stadium at the mask's anchor, at the showcase's level, near the middle of the meadow, the town's biggest building
        val pitch = city.slots.single { it.type == BuildingType.PITCH }
        assertEquals(stadium, pitch.level); assertEquals(lands.stadium, pitch.x to pitch.y)
        val ps = BuildingCatalog.spec(BuildingType.PITCH)
        for (s in city.slots.filter { it.type != BuildingType.PITCH && BuildingCatalog.spec(it.type).island == null })
            assertTrue(BuildingCatalog.spec(s.type).let { it.width * it.height } < ps.width * ps.height, "${s.type} is as big as the stadium")
        val mx = land.cells.sumOf { it.first + 0.5 } / land.area; val my = land.cells.sumOf { it.second + 0.5 } / land.area
        assertTrue(Math.hypot(pitch.x + ps.width / 2.0 - mx, pitch.y + ps.height / 2.0 - my) < 6, "the stadium is off the meadow's middle ($mx, $my)")
        // nothing touches the stadium, and no two buildings share an edge (each stands on its own lawn)
        val solid = city.slots.filter { it.type != BuildingType.ROAD && it.type != BuildingType.DECOR }
        for (a in solid) for (b in solid) if (a !== b) {
            val sa = BuildingCatalog.spec(a.type); val sb = BuildingCatalog.spec(b.type)
            val gapX = maxOf(b.x - (a.x + sa.width), a.x - (b.x + sb.width)); val gapY = maxOf(b.y - (a.y + sa.height), a.y - (b.y + sb.height))
            assertTrue(maxOf(gapX, gapY) >= 1, "${a.type} at ${a.x},${a.y} touches ${b.type} at ${b.x},${b.y}")
        }
        assertEquals(stadium, Stadium.level(city.slots.map { com.arenacity.domain.model.Building(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), it.type, it.level, it.x, it.y, 0,
            com.arenacity.domain.model.BuildingState.IDLE, null, java.time.Instant.EPOCH, java.time.Instant.EPOCH, java.time.Instant.EPOCH) }))
        // every type the stadium allows is there at least once, the club's buildings every copy (the town as many as fit
        // spaciously); an arena where its island is open (a mask without that island has none)
        for (type in BuildingType.values()) {
            val spec = BuildingCatalog.spec(type)
            if (type == BuildingType.FENCE || spec.countAt(stadium) == 0) continue
            if (spec.island != null && open.none { it.sport == spec.island }) { assertEquals(null, counts[type], "$type without its island"); continue }
            assertTrue((counts[type] ?: 0) >= 1, "$type at stadium $stadium")
            if (type !in setOf(BuildingType.HOUSE, BuildingType.BACKYARD_COURT, BuildingType.ROAD, BuildingType.DECOR))
                assertEquals(spec.countAt(stadium), counts[type], "every $type at stadium $stadium")
        }
        // the islands hold their arenas only: one in each open island whose arena the stadium allows
        for (s in city.slots.filter { BuildingCatalog.spec(it.type).island != null }) {
            val isle = lands.island(BuildingCatalog.spec(s.type).island!!)!!
            assertTrue(isle in open, "${s.type} on a closed island")
            assertEquals(1, city.slots.count { isle.fits(footprint(it.type, it.x, it.y, 0)) }, "${isle.id} holds its arena only")
        }
        // the streets: one network on the meadow; the ring round the plaza at every stadium (cells c - 13 .. c + 12 round
        // the stadium's middle c), at the top the boulevards on the axes from the ring out to the shore
        val roads = city.slots.filter { it.type == BuildingType.ROAD }.map { it.x to it.y }.toSet()
        val seen = HashSet<Pair<Int, Int>>(); val queue = ArrayDeque(listOf(roads.first())); seen += roads.first()
        while (queue.isNotEmpty()) { val (x, y) = queue.removeFirst(); for (n in listOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1)) if (n in roads && seen.add(n)) queue.addLast(n) }
        assertEquals(roads, seen, "the streets are one network at stadium $stadium")
        val cx = pitch.x + ps.width / 2; val cy = pitch.y + ps.height / 2
        for (i in -13..12) for (p in listOf(cx - 13 to cy + i, cx + 12 to cy + i, cx + i to cy - 13, cx + i to cy + 12))
            if (land.has(p.first, p.second)) assertTrue(p in roads, "the ring at $p, stadium $stadium")
        if (stadium >= 8) for ((dx, dy) in listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)) for (k in listOf(-1, 0)) {
            // from the ring outwards, cell by cell while the meadow lasts
            var x = if (dx == 0) cx + k else if (dx < 0) cx - 14 else cx + 13
            var y = if (dy == 0) cy + k else if (dy < 0) cy - 14 else cy + 13
            while (land.has(x, y)) { assertTrue(x to y in roads, "the boulevard at $x,$y, stadium $stadium"); x += dx; y += dy }
        }
        // ... and on the shipped mask the streets reach every bridge's foot (where its crossing, painted by the bridge
        // building, meets the meadow: land_mask.json anchors.bridges)
        if (stadium >= 8 && lands === Lands.MAP) for ((id, f) in lands.bridges) assertTrue(f in roads, "no road reaches the $id bridge's foot $f, stadium $stadium")
        // every tree the stadium allows stands on a kerb or the shore
        assertEquals(BuildingCatalog.spec(BuildingType.DECOR).countAt(stadium), counts[BuildingType.DECOR] ?: 0, "trees at stadium $stadium")
        // a pavement cell between every building and the streets (the street trees stand there), and along the shore (an
        // arena's lawn on its island)
        for (s in solid) { val sp = BuildingCatalog.spec(s.type); val own = sp.island?.let { lands.island(it)!! } ?: land
            for (x in s.x until s.x + sp.width) for (y in s.y until s.y + sp.height)
            for (n in listOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1)) {
                assertTrue(n !in roads, "${s.type} at ${s.x},${s.y} fronts the street at $n")
                if (s.type != BuildingType.PITCH) assertTrue(own.has(n.first, n.second), "${s.type} at ${s.x},${s.y} stands on the shore at $n")
            } }
        // a whole town at the top, the club and the first streets of the town at stadium 5 (the main island's)
        val built = solid.filter { BuildingCatalog.spec(it.type).island == null }.sumOf { BuildingCatalog.spec(it.type).width * BuildingCatalog.spec(it.type).height }
        // (the sport town's buildings are smaller than the old towers and offices: a fifth of the meadow at the top)
        assertTrue(built * 100 / land.area in (if (stadium == Stadium.MAX_LEVEL) 20..45 else 10..30), "$built of ${land.area} cells built at stadium $stadium")
    }

    @Test fun `stadium 5 showcase fits the shipped land`() = check(5, Lands.MAP)
    @Test fun `top showcase fits the shipped land`() = check(10, Lands.MAP)
    @Test fun `stadium 5 showcase fits an organic meadow`() = check(5, TestLand.ORGANIC)
    @Test fun `top showcase fits an organic meadow`() = check(10, TestLand.ORGANIC)
    @Test fun `both showcases fit the old square with a corner cut off`() { check(5, TestLand.NOTCHED); check(10, TestLand.NOTCHED) }

    @Test
    fun `the showcases are rebuilt on a new land mask and need no migration`() = kotlinx.coroutines.runBlocking {
        val clubs = com.arenacity.testutil.FakeClubRepository(); val buildings = com.arenacity.testutil.FakeBuildingRepository()
        val users = com.arenacity.testutil.FakeUserRepository(); val clock = com.arenacity.testutil.FixedClock()
        val zones = com.arenacity.testutil.FakeZoneRepository()
        com.arenacity.domain.usecase.social.ShowcaseService(users, clubs, buildings, clock, Lands.SQUARE, zones).ensure()
        com.arenacity.domain.usecase.social.ShowcaseService(users, clubs, buildings, clock, TestLand.ORGANIC, zones).ensure()
        for (d in com.arenacity.domain.usecase.social.ShowcaseService.DEFS) {
            val have = buildings.listByClub(d.id)
            val city = Showcase.city(d.stadium, TestLand.ORGANIC)
            assertEquals(city.slots.size, have.size)
            // its side islands open (the zones OPEN, so a visitor sees them), the arenas on them, the rest on the meadow
            val open = com.arenacity.domain.usecase.club.openZoneSports(zones, d.id, clock.now())
            assertEquals(city.zones, open)
            val land = TestLand.ORGANIC.of(open)
            assertTrue(have.all { TestLand.ORGANIC.allows(land, it.type, footprint(it.type, it.gridX, it.gridY, it.rotation)) }, "${d.name} stands on the organic land")
            assertTrue(have.any { it.type == BuildingType.BASKETBALL_ARENA }, "${d.name} has its basketball arena")
            assertEquals(TestLand.ORGANIC.id, clubs.rows.getValue(d.id).landMask, "${d.name} needs no land migration")
        }
        // the top one has every arena on its island; stadium 5 the basketball and volleyball ones (tennis opens at 7, padel at 9)
        assertEquals(setOf(BuildingType.BASKETBALL_ARENA, BuildingType.VOLLEYBALL_ARENA, BuildingType.TENNIS_ARENA, BuildingType.PADEL_ARENA),
            buildings.listByClub(com.arenacity.domain.usecase.social.ShowcaseService.DEFS.last().id).filter { BuildingCatalog.spec(it.type).island != null }.map { it.type }.toSet())
        assertEquals(setOf(BuildingType.BASKETBALL_ARENA, BuildingType.VOLLEYBALL_ARENA),
            buildings.listByClub(com.arenacity.domain.usecase.social.ShowcaseService.DEFS.first().id).filter { BuildingCatalog.spec(it.type).island != null }.map { it.type }.toSet())
    }
}
