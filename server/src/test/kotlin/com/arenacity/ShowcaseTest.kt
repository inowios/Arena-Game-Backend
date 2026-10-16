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
        // the main island and the peoples' islands whose bridge the stadium allows
        val open = Showcase.islands(stadium, lands)
        assertEquals(setOf(land.id) + open.map { it.id }, city.regions, "the main island and the open side islands")
        assertEquals(open.mapNotNull { it.people }.toSet(), city.zones)
        assertEquals(com.arenacity.domain.model.People.entries.filter { Zones.spec(it).requiredStadium <= stadium && lands.island(it) != null }.toSet(), city.zones)
        val ground = LandSet(listOf(land) + open)
        val cells = HashMap<Pair<Int, Int>, BuildingType>()
        for (s in city.slots) {
            val spec = BuildingCatalog.spec(s.type)
            assertTrue(Grid.aligned(s.x, s.y), "${s.type} at ${s.x},${s.y} is off the build grid's tiles")
            assertTrue(s.level in 1..spec.capAt(stadium).coerceAtLeast(1), "${s.type} level ${s.level} over the cap at stadium $stadium")
            // everything on the main island (the peoples' islands hold no buildings of the town)
            val f = footprint(s.type, s.x, s.y, s.rot)
            assertTrue(land.fits(f), "${s.type} at ${s.x},${s.y} is off the island")
            assertTrue(lands.allows(ground, s.type, f))
            for (x in f.x until f.x + f.w) for (y in f.y until f.y + f.h)
                assertTrue(cells.put(x to y, s.type) == null, "${s.type} overlaps ${cells[x to y]} at $x,$y")
        }
        // SHOWCASE_MAP=1: print the town (the island's cells as dots)
        if (System.getenv("SHOWCASE_MAP") != null) {
            val ch = mapOf(BuildingType.ROAD to '#', BuildingType.DECOR to '*', BuildingType.PITCH to 'P', BuildingType.HOUSE to 'h', BuildingType.ACADEMY to 'A',
                BuildingType.TRAINING_CENTER to 'R', BuildingType.MEDICAL_CENTER to 'm', BuildingType.TICKET_OFFICE to 'T', BuildingType.SHOPPING_CENTER to 'S',
                BuildingType.HALL_OF_FAME to 'F', BuildingType.BACKYARD_COURT to 'b', BuildingType.FAN_ZONE to 'z', BuildingType.MEDIA_CENTER to 'M',
                BuildingType.SPONSOR_OFFICE to 'o', BuildingType.HOTEL to 'H', BuildingType.ATHLETES_RESIDENCE to 'r', BuildingType.YOUTH_SPORTS_SCHOOL to 'y',
                BuildingType.SPORTS_SCIENCE_LAB to 'l', BuildingType.SCHOOL to 's', BuildingType.TRANSPORT_HUB to 't', BuildingType.SCOUTING_OFFICE to 'c',
                BuildingType.ANALYTICS_CENTER to 'a')
            val built = city.slots.filter { it.type != BuildingType.ROAD && it.type != BuildingType.DECOR }
                .sumOf { BuildingCatalog.spec(it.type).width * BuildingCatalog.spec(it.type).height }
            println("stadium $stadium on ${land.area} cells: " + city.slots.groupingBy { it.type }.eachCount().entries.sortedBy { it.key.ordinal }.joinToString { "${it.key}=${it.value}" } + ", built $built cells (${built * 100 / land.area} %)")
            for (y in land.y0 until land.y0 + land.h) println((land.x0 until land.x0 + land.w).joinToString("") { x -> cells[x to y]?.let { (ch[it] ?: it.name.first()).toString() } ?: if (land.has(x, y)) "." else " " })
        }
        val counts = city.slots.groupingBy { it.type }.eachCount()
        for ((type, n) in counts) assertTrue(n <= BuildingCatalog.spec(type).countAt(stadium), "$type: $n > ${BuildingCatalog.spec(type).countAt(stadium)}")
        // the stadium at the mask's anchor, at the showcase's level, near the middle of the meadow, the town's biggest building
        val pitch = city.slots.single { it.type == BuildingType.PITCH }
        assertEquals(stadium, pitch.level); assertEquals(lands.stadium, pitch.x to pitch.y)
        val ps = BuildingCatalog.spec(BuildingType.PITCH)
        for (s in city.slots.filter { it.type != BuildingType.PITCH })
            assertTrue(BuildingCatalog.spec(s.type).let { it.width * it.height } < ps.width * ps.height, "${s.type} is as big as the stadium")
        val mx = land.cells.sumOf { it.first + 0.5 } / land.area; val my = land.cells.sumOf { it.second + 0.5 } / land.area
        assertTrue(Math.hypot(pitch.x + ps.width / 2.0 - mx, pitch.y + ps.height / 2.0 - my) < 6, "the stadium is off the meadow's middle ($mx, $my)")
        // nothing touches the stadium, and no two buildings share an edge (each stands on its own lawn)
        val solid = city.slots.filter { it.type != BuildingType.ROAD && it.type != BuildingType.DECOR }
        for (a in solid) for (b in solid) if (a !== b) {
            val sa = footprint(a.type, a.x, a.y, a.rot); val sb = footprint(b.type, b.x, b.y, b.rot)
            val gapX = maxOf(b.x - (a.x + sa.w), a.x - (b.x + sb.w)); val gapY = maxOf(b.y - (a.y + sa.h), a.y - (b.y + sb.h))
            assertTrue(maxOf(gapX, gapY) >= Grid.TILE, "${a.type} at ${a.x},${a.y} touches ${b.type} at ${b.x},${b.y}")
        }
        assertEquals(stadium, Stadium.level(city.slots.map { com.arenacity.domain.model.Building(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), it.type, it.level, it.x, it.y, 0,
            com.arenacity.domain.model.BuildingState.IDLE, null, java.time.Instant.EPOCH, java.time.Instant.EPOCH, java.time.Instant.EPOCH) }))
        // on the shipped land every type the stadium allows is there at least once, the club's buildings every copy (the
        // town's homes and yard courts as many as fit spaciously). The test meadows are smaller: since the bigger buildings
        // of 16 October (the academy 8×8, the training centre 12×8 … beside the 16×16 stadium) a full town no longer fits
        // them, so there only what fits is checked — no more copies than allowed (above) and most of the types (three in four)
        val allowed = BuildingType.values().filter { it != BuildingType.FENCE && BuildingCatalog.spec(it).countAt(stadium) > 0 }
        if (lands === Lands.MAP) for (type in allowed) {
            assertTrue((counts[type] ?: 0) >= 1, "$type at stadium $stadium")
            if (type !in setOf(BuildingType.HOUSE, BuildingType.BACKYARD_COURT, BuildingType.ROAD, BuildingType.DECOR))
                assertEquals(BuildingCatalog.spec(type).countAt(stadium), counts[type], "every $type at stadium $stadium")
        } else assertTrue(allowed.count { (counts[it] ?: 0) >= 1 } * 4 >= allowed.size * 3,
            "most types at stadium $stadium: missing ${allowed.filter { (counts[it] ?: 0) == 0 }}")
        // the peoples' islands hold nothing of the town (their settlements are the client's)
        for (isle in open) assertEquals(0, city.slots.count { isle.fits(footprint(it.type, it.x, it.y, 0)) }, "${isle.id} holds nothing")
        // the streets: one network on the meadow; the ring round the plaza at every stadium (tiles c - RING .. c + RING - 1
        // round the stadium's middle tile c), at the top the boulevards on the axes from the ring out to the shore — all in
        // tiles of the build grid (Grid.TILE cells), the meadow's whole tiles
        val T = Grid.TILE
        val tland = land.tiles()
        val roads = city.slots.filter { it.type == BuildingType.ROAD }.map { it.x / T to it.y / T }.toSet()
        val seen = HashSet<Pair<Int, Int>>(); val queue = ArrayDeque(listOf(roads.first())); seen += roads.first()
        while (queue.isNotEmpty()) { val (x, y) = queue.removeFirst(); for (n in listOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1)) if (n in roads && seen.add(n)) queue.addLast(n) }
        assertEquals(roads, seen, "the streets are one network at stadium $stadium")
        val cx = pitch.x / T + ps.width / T / 2; val cy = pitch.y / T + ps.height / T / 2
        val r = Showcase.RING
        for (i in -r until r) for (p in listOf(cx - r to cy + i, cx + r - 1 to cy + i, cx + i to cy - r, cx + i to cy + r - 1))
            if (tland.has(p.first, p.second)) assertTrue(p in roads, "the ring at tile $p, stadium $stadium")
        // the boulevards (from the ring outwards, cell by cell while the meadow lasts) are laid, with the bridges' spurs, after
        // the ring and the plaza's approaches while the road allowance has room: all of them or none (16 October: the
        // allowance 90 tiles, was 140, and the ring of the 16×16 stadium takes most of it — none then; checked that they
        // really did not fit)
        val boulevards = LinkedHashSet<Pair<Int, Int>>()
        if (stadium >= 8) for ((dx, dy) in listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)) for (k in listOf(0)) {
            var x = if (dx == 0) cx + k else if (dx < 0) cx - r - 1 else cx + r
            var y = if (dy == 0) cy + k else if (dy < 0) cy - r - 1 else cy + r
            while (tland.has(x, y)) { boulevards += x to y; x += dx; y += dy }
        }
        val laid = boulevards.any { it in roads }
        if (laid) for (p in boulevards) assertTrue(p in roads, "the boulevard at tile $p, stadium $stadium")
        else if (stadium >= 8) assertTrue(roads.size + boulevards.size > BuildingCatalog.spec(BuildingType.ROAD).countAt(stadium), "the boulevards fit the allowance but are not laid, stadium $stadium")
        // ... and on the shipped mask the streets reach every bridge's foot (with the boulevards: the spurs are laid with them) (where its crossing, painted by the bridge
        // building, meets the meadow: land_mask.json anchors.bridges)
        // (its tile, or the tile next to it where the foot's own tile is not wholly meadow)
        if (stadium >= 8 && lands === Lands.MAP && laid) for ((id, f) in lands.bridges) {
            val fx = Math.floorDiv(f.first, T); val fy = Math.floorDiv(f.second, T)
            assertTrue((fx - 1..fx + 1).any { x -> (fy - 1..fy + 1).any { y -> x to y in roads } }, "no road reaches the $id bridge's foot $f, stadium $stadium")
        }
        // every tree the stadium allows stands on a kerb or the shore
        assertEquals(BuildingCatalog.spec(BuildingType.DECOR).countAt(stadium), counts[BuildingType.DECOR] ?: 0, "trees at stadium $stadium")
        // a lawn tile along the shore round every building; on tiles a building may front the street (14 October, pass 3:
        // a 20 m pavement took too much of the meadow)
        for (s in solid) { val sp = footprint(s.type, s.x, s.y, s.rot); val own = land.tiles()
            for (x in s.x / T until (s.x + sp.w) / T) for (y in s.y / T until (s.y + sp.h) / T)
            for (n in listOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1)) {
                if (s.type != BuildingType.PITCH) assertTrue(own.has(n.first, n.second), "${s.type} at ${s.x},${s.y} stands on the shore at $n")
            } }
        // a whole town at the top, the club and the first streets of the town at stadium 5 (the main island's)
        val built = solid.sumOf { BuildingCatalog.spec(it.type).width * BuildingCatalog.spec(it.type).height }
        // (the sport town's buildings are smaller than the old towers and offices: a fifth of the meadow at the top; their
        // footprints doubled on 14 October, a third of the meadow then; grown again on 16 October, up to half the smaller
        // meadows at stadium 5 too)
        assertTrue(built * 100 / land.area in (if (stadium == Stadium.MAX_LEVEL) 20..50 else 10..50), "$built of ${land.area} cells built at stadium $stadium")
    }

    @Test fun `stadium 5 showcase fits the shipped land`() = check(5, Lands.MAP)
    @Test fun `top showcase fits the shipped land`() = check(10, Lands.MAP)
    @Test fun `stadium 5 showcase fits an organic meadow`() = check(5, TestLand.ORGANIC)
    @Test fun `top showcase fits an organic meadow`() = check(10, TestLand.ORGANIC)
    // the old square (not the shipped land) holds the stadium-5 town; a full top town no longer fits it beside the 16×16
    // stadium (16 October) — the shipped land and the organic meadow keep the top showcase
    @Test fun `the stadium 5 showcase fits the old square with a corner cut off`() = check(5, TestLand.NOTCHED)

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
            // its peoples' islands open (the zones OPEN, so a visitor sees them, the peoples back), the town on the meadow
            val open = com.arenacity.domain.usecase.club.openPeoples(zones, d.id, clock.now())
            assertEquals(city.zones, open)
            val land = TestLand.ORGANIC.of(open)
            assertTrue(have.all { TestLand.ORGANIC.allows(land, it.type, footprint(it.type, it.gridX, it.gridY, it.rotation)) }, "${d.name} stands on the organic land")
            assertEquals(TestLand.ORGANIC.id, clubs.rows.getValue(d.id).landMask, "${d.name} needs no land migration")
        }
        // the top one has every people back; stadium 5 the beastfolk and the stone people (the androids return at 7, the shadows at 10)
        assertEquals(com.arenacity.domain.model.People.entries.toSet(), com.arenacity.domain.usecase.club.openPeoples(zones, com.arenacity.domain.usecase.social.ShowcaseService.DEFS.last().id, clock.now()))
        assertEquals(setOf(com.arenacity.domain.model.People.BEAST, com.arenacity.domain.model.People.STONE), com.arenacity.domain.usecase.club.openPeoples(zones, com.arenacity.domain.usecase.social.ShowcaseService.DEFS.first().id, clock.now()))
    }
}
