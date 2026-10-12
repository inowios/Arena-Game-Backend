package com.arenacity

import com.arenacity.domain.rules.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShowcaseTest {
    private fun check(stadium: Int) {
        val city = Showcase.city(stadium)
        val cells = HashMap<Pair<Int, Int>, BuildingType>()
        for (s in city.slots) {
            val spec = BuildingCatalog.spec(s.type)
            assertTrue(s.level in 1..spec.capAt(stadium).coerceAtLeast(1), "${s.type} level ${s.level} over the cap at stadium $stadium")
            for (x in s.x until s.x + spec.width) for (y in s.y until s.y + spec.height) {
                assertTrue(Land.parcelOf(x, y) in city.parcels && x >= Land.ORIGIN && y >= Land.ORIGIN, "${s.type} at $x,$y is off the land")
                assertTrue(cells.put(x to y, s.type) == null, "${s.type} overlaps ${cells[x to y]} at $x,$y")
            }
        }
        val counts = city.slots.groupingBy { it.type }.eachCount()
        // the avenues are a full grid (the showcase is a planned town); everything else stays within the allowance
        for ((type, n) in counts) if (type != BuildingType.ROAD) assertTrue(n <= BuildingCatalog.spec(type).countAt(stadium), "$type: $n > ${BuildingCatalog.spec(type).countAt(stadium)}")
        assertEquals(stadium, city.slots.single { it.type == BuildingType.PITCH }.level)
        // nothing touches the stadium, and no two buildings share an edge (each stands on its own lawn)
        val solid = city.slots.filter { it.type != BuildingType.ROAD && it.type != BuildingType.DECOR }
        for (a in solid) for (b in solid) if (a !== b) {
            val sa = BuildingCatalog.spec(a.type); val sb = BuildingCatalog.spec(b.type)
            val gapX = maxOf(b.x - (a.x + sa.width), a.x - (b.x + sb.width)); val gapY = maxOf(b.y - (a.y + sa.height), a.y - (b.y + sb.height))
            assertTrue(maxOf(gapX, gapY) >= 1, "${a.type} at ${a.x},${a.y} touches ${b.type} at ${b.x},${b.y}")
        }
        assertEquals(stadium, Stadium.level(city.slots.map { com.arenacity.domain.model.Building(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), it.type, it.level, it.x, it.y, 0,
            com.arenacity.domain.model.BuildingState.IDLE, null, java.time.Instant.EPOCH, java.time.Instant.EPOCH, java.time.Instant.EPOCH) }))
        // every type the stadium allows is there, as many as allowed (the land has room)
        for (type in BuildingType.values()) {
            val want = BuildingCatalog.spec(type).countAt(stadium)
            if (type == BuildingType.FENCE || type == BuildingType.ROAD || want == 0) continue
            if (stadium >= Stadium.MAX_LEVEL && type in setOf(BuildingType.HOUSE, BuildingType.PARK, BuildingType.APARTMENT)) { assertTrue((counts[type] ?: 0) in 1..want, "$type at the top"); continue }
            assertEquals(want, counts[type] ?: 0, "$type at stadium $stadium")
        }
        if (System.getenv("SHOWCASE_MAP") != null) {
            val ch = mapOf(BuildingType.ROAD to '#', BuildingType.DECOR to '*', BuildingType.PITCH to 'P', BuildingType.HOUSE to 'h', BuildingType.APARTMENT to 'a',
                BuildingType.OFFICE to 'o', BuildingType.PARK to 'p')
            val lo = Land.ORIGIN; val hi = Land.ORIGIN + Land.CELLS
            println("stadium $stadium:")
            for (y in lo until hi) println((lo until hi).joinToString("") { x -> cells[x to y]?.let { (ch[it] ?: it.name.first().lowercaseChar().uppercaseChar()).toString() } ?: if (Land.parcelOf(x, y) in city.parcels) "." else " " })
        }
    }

    @Test fun `stadium 5 showcase is built to the limits`() = check(5)
    @Test fun `top showcase is built to the limits`() = check(10)
}
