package com.arenacity.domain.rules

/**
 * Showcase cities (the owner, 12 October: «every player gets two friends whose cities are built — one with a level-5
 * stadium, one built to the maximum — so players see what their city can become»). Two bot clubs shared by everyone,
 * laid out from the real limits: every building type the stadium level allows, as many copies as it allows, at the
 * highest level it allows, on the land bought, with avenues along the parcel edges. Pure: the same input, the same city.
 */
object Showcase {
    data class Slot(val type: BuildingType, val level: Int, val x: Int, val y: Int)
    data class City(val stadium: Int, val parcels: Set<Pair<Int, Int>>, val slots: List<Slot>)

    /** The land each showcase owns: the 3×3 parcels round home for stadium 5, the whole 6×6 field at the top. */
    fun parcels(stadium: Int): Set<Pair<Int, Int>> =
        if (stadium >= Stadium.MAX_LEVEL) (0 until Land.PARCELS).flatMap { c -> (0 until Land.PARCELS).map { r -> c to r } }.toSet()
        else (1..3).flatMap { c -> (1..3).map { r -> c to r } }.toSet()

    /** Club buildings first (nearest the stadium), the town after them, outwards. */
    private val ORDER = listOf(
        BuildingType.ACADEMY, BuildingType.TRAINING_CENTER, BuildingType.MEDICAL_CENTER, BuildingType.MUSEUM,
        BuildingType.BASKETBALL_COURT, BuildingType.TENNIS_COURT, BuildingType.TICKET_OFFICE, BuildingType.CLUB_SHOP,
        BuildingType.CAFE, BuildingType.PARKING, BuildingType.OFFICE, BuildingType.APARTMENT, BuildingType.PARK, BuildingType.HOUSE,
    )

    fun city(stadium: Int): City {
        val parcels = parcels(stadium)
        fun owns(x: Int, y: Int): Boolean {
            if (x < Land.ORIGIN || y < Land.ORIGIN) return false
            val p = Land.parcelOf(x, y); return Land.inField(p.first, p.second) && p in parcels
        }
        val cols = parcels.map { it.first }.toSortedSet(); val rows = parcels.map { it.second }.toSortedSet()
        val x0 = Land.cell(cols.first()); val x1 = Land.cell(cols.last() + 1)
        val y0 = Land.cell(rows.first()); val y1 = Land.cell(rows.last() + 1)
        val cx = (x0 + x1) / 2.0; val cy = (y0 + y1) / 2.0
        fun dist(x: Double, y: Double) = Math.hypot(x - cx, y - cy)

        // avenues on the inner parcel edges, trimmed from their far ends to the road allowance
        val roadCells = mutableListOf<Pair<Int, Int>>()
        for (c in cols.drop(1)) for (y in y0 until y1) if (owns(Land.cell(c), y)) roadCells += Land.cell(c) to y
        for (r in rows.drop(1)) for (x in x0 until x1) if (owns(x, Land.cell(r))) roadCells += x to Land.cell(r)
        val roadCap = BuildingCatalog.spec(BuildingType.ROAD).countAt(stadium)
        val roads = roadCells.distinct().sortedBy { dist(it.first + 0.5, it.second + 0.5) }.take(roadCap).toSet()

        val taken = HashSet<Pair<Int, Int>>(roads)
        val slots = mutableListOf<Slot>()
        fun free(x: Int, y: Int, w: Int, h: Int, margin: Int): Boolean {
            for (i in x until x + w) for (j in y until y + h) if (!owns(i, j) || (i to j) in taken) return false
            // keep a lane between buildings (roads may touch)
            for (i in x - margin until x + w + margin) for (j in y - margin until y + h + margin)
                if ((i to j) in taken && (i to j) !in roads && (i !in x until x + w || j !in y until y + h)) return false
            return true
        }
        fun place(type: BuildingType, level: Int, margin: Int = 1): Boolean {
            val s = BuildingCatalog.spec(type)
            val spots = ArrayList<Triple<Int, Int, Double>>()
            for (x in x0..x1 - s.width) for (y in y0..y1 - s.height) spots += Triple(x, y, dist(x + s.width / 2.0, y + s.height / 2.0))
            spots.sortBy { it.third }
            for ((x, y, _) in spots) if (free(x, y, s.width, s.height, margin)) {
                for (i in x until x + s.width) for (j in y until y + s.height) taken += i to j
                slots += Slot(type, level, x, y); return true
            }
            return false
        }

        place(BuildingType.PITCH, stadium, margin = 2)
        for (type in ORDER) {
            val s = BuildingCatalog.spec(type)
            repeat(s.countAt(stadium)) { place(type, s.capAt(stadium)) }
        }
        // trees along the avenues, every third free kerb cell, up to the decor allowance
        val decor = BuildingCatalog.spec(BuildingType.DECOR)
        var trees = decor.countAt(stadium); var n = 0
        for ((rx, ry) in roads.sortedBy { dist(it.first + 0.5, it.second + 0.5) }) {
            if (trees <= 0) break
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val p = rx + dx to ry + dy
                if (trees > 0 && owns(p.first, p.second) && p !in taken && (n++ % 3 == 0)) { taken += p; slots += Slot(BuildingType.DECOR, decor.capAt(stadium), p.first, p.second); trees-- }
            }
        }
        for ((x, y) in roads) slots += Slot(BuildingType.ROAD, BuildingCatalog.spec(BuildingType.ROAD).capAt(stadium).coerceAtLeast(1), x, y)
        return City(stadium, parcels, slots)
    }
}
