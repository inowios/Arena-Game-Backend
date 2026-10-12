package com.arenacity.domain.rules

/**
 * Showcase cities (the owner, 12 October: «every player gets two friends whose cities are built — one with a level-5
 * stadium, one built to the maximum — so players see what their city can become»; then «a beautiful city, not buildings
 * on top of each other»). Two bot clubs shared by everyone. Land is not for sale since the archipelago, so both stand on
 * the plot alone — since v2 (12 October, docs/art/PAINTED-MAP.md) the sixteen home parcels, 48×48 cells — laid out as a
 * planned town round the stadium:
 *  - the stadium in the exact middle on its plaza; a ring avenue round the plaza, boulevards two cells wide on the axes
 *    from the stadium out to the plot's edges (where the bridges land) and the ring's lines run on to the edges: a grid of
 *    4×4 blocks of 11×11 cells. The road allowance lays the ring, the plaza's approaches, the boulevards and the outer
 *    avenues in that order (stadium 5: the ring; from stadium 8 the whole grid); a line not laid yet stays lawn;
 *  - every block keeps a pavement cell along its streets and the plot's edge, where the trees stand; its buildings stand
 *    in rows with a lawn cell at least between them, the spare lawn spread evenly over the block;
 *  - the club round the stadium: the museum, ticket offices, shops and cafés on the plaza facing the stands, the courts at
 *    its ends, the academy, training and medical centres and the parking in the blocks next to it (every other block going
 *    round); the town outwards — offices and towers in the other blocks along the boulevards, houses and parks in the
 *    corner blocks once those are full;
 *  - trees along the boulevards and the plaza's approaches first, then the ring, the avenues and the plot's edge.
 * Within the game's limits (no type over its count or level cap at that stadium); the town takes as many copies as fit
 * with that spacing (at the top fewer houses and parks than allowed, so it stays spacious). Pure: same input, same city.
 */
object Showcase {
    data class Slot(val type: BuildingType, val level: Int, val x: Int, val y: Int)
    data class City(val stadium: Int, val parcels: Set<Pair<Int, Int>>, val slots: List<Slot>)

    /** Cells from the plot's edge to the ring avenue: 11 makes every block 11×11 (the ring on cells 2 and 27). */
    private const val RING = 11

    /** The stadium's own trade on the plaza, facing the stands: the museum, then the earners in turn. */
    private val PLAZA = listOf(BuildingType.MUSEUM, BuildingType.TICKET_OFFICE, BuildingType.CLUB_SHOP, BuildingType.CAFE)
    /** The courts at the stadium's ends. */
    private val COURTS = listOf(BuildingType.BASKETBALL_COURT, BuildingType.TENNIS_COURT)
    /** The club's campus in the blocks next to the plaza. */
    private val CAMPUS = listOf(BuildingType.TRAINING_CENTER, BuildingType.ACADEMY, BuildingType.MEDICAL_CENTER, BuildingType.PARKING)
    /** Downtown along the boulevards: offices and towers in turn. */
    private val TOWERS = listOf(BuildingType.OFFICE, BuildingType.APARTMENT)
    /** The suburbs: two houses, then a park. */
    private val HOMES = listOf(BuildingType.HOUSE, BuildingType.HOUSE, BuildingType.PARK)

    /** The cells [x0, x1) × [y0, y1) a block's buildings stand on (its pavement already left out) and what stands there. */
    private class Lot(val x0: Int, val x1: Int, val y0: Int, val y1: Int) {
        val cx = (x0 + x1) / 2.0; val cy = (y0 + y1) / 2.0
        val types = mutableListOf<BuildingType>()
    }

    /**
     * Where things of [sizes] start along a line with [free] spare cells: the spare cells shared evenly before, between and
     * after them when every gap gets one, else between them only (a lawn cell at least); the middle gaps take what is
     * left over. Null when they do not fit.
     */
    private fun spread(free: Int, sizes: List<Int>): List<Int>? {
        val n = sizes.size
        if (free < n - 1) return null
        val gaps = IntArray(n + 1)
        val open = if (n == 1 || free >= n + 1) (0..n).toList() else (1 until n).toList()
        val order = open.sortedBy { Math.abs(it - n / 2.0) }
        for (k in 0 until free) gaps[order[k % order.size]]++
        var p = 0
        return sizes.mapIndexed { i, s -> p += gaps[i]; val at = p; p += s; at }
    }

    /**
     * Where [types] stand on [lot] (top-left cells, in order), or null when they do not fit: in rows along the lot's long
     * side, each building in the first row with room, a lawn cell at least between neighbours and between rows, the spare
     * lawn spread evenly; the first row, and the first building of a row, nearest the stadium.
     */
    private fun arrange(lot: Lot, types: List<BuildingType>): List<Pair<Int, Int>>? {
        val alongX = lot.x1 - lot.x0 >= lot.y1 - lot.y0
        val len = if (alongX) lot.x1 - lot.x0 else lot.y1 - lot.y0
        val deep = if (alongX) lot.y1 - lot.y0 else lot.x1 - lot.x0
        val specs = types.map { BuildingCatalog.spec(it) }
        val along = specs.map { if (alongX) it.width else it.height }
        val across = specs.map { if (alongX) it.height else it.width }
        val rows = mutableListOf<MutableList<Int>>()
        for (i in types.indices) {
            if (along[i] > len) return null
            val row = rows.firstOrNull { r -> r.sumOf { along[it] } + r.size + along[i] <= len }
            if (row != null) row += i else rows += mutableListOf(i)
        }
        val depths = rows.map { r -> r.maxOf { across[it] } }
        val rowAt = spread(deep - depths.sum(), depths) ?: return null
        val nearHighX = lot.cx < Grid.CENTRE; val nearHighY = lot.cy < Grid.CENTRE
        val at = arrayOfNulls<Pair<Int, Int>>(types.size)
        for ((k, r) in rows.withIndex()) {
            val starts = spread(len - r.sumOf { along[it] }, r.map { along[it] }) ?: return null
            for ((j, i) in r.withIndex()) {
                val a = starts[j]; val b = rowAt[k] + (depths[k] - across[i]) / 2
                // from the lot's corner nearest the stadium: u along x, v along y
                val u = if (alongX) a else b; val v = if (alongX) b else a
                val x = if (nearHighX) lot.x1 - u - specs[i].width else lot.x0 + u
                val y = if (nearHighY) lot.y1 - v - specs[i].height else lot.y0 + v
                at[i] = x to y
            }
        }
        return at.map { it!! }
    }

    /**
     * Deals [queue] out over [lots] in its order: each building to the lot where it fits that has none of its kind yet and
     * the fewest buildings (the first such lot on a tie), so blocks fill evenly and the buildings of a block differ.
     * What fits nowhere stays in [queue].
     */
    private fun deal(lots: List<Lot>, queue: MutableList<BuildingType>) {
        val rest = mutableListOf<BuildingType>()
        for (t in queue) {
            val lot = lots.filter { arrange(it, it.types + t) != null }.minWithOrNull(compareBy({ t in it.types }, { it.types.size }))
            if (lot != null) lot.types += t else rest += t
        }
        queue.clear(); queue += rest
    }

    fun city(stadium: Int): City {
        val lo = Grid.lo(Grid.PLOT); val hi = Grid.hi(Grid.PLOT); val c = Grid.CENTRE
        val pitch = BuildingCatalog.spec(BuildingType.PITCH)
        val px = c - pitch.width / 2; val py = c - pitch.height / 2
        // the grid: the ring's lines (cells 2 and 27), the boulevards on the axes (cells 14 and 15, the plot's middle between them)
        val r0 = lo + RING; val r1 = hi - 1 - RING; val a0 = c - 1; val a1 = c
        fun cells(xs: IntRange, ys: IntRange) = xs.flatMap { x -> ys.map { y -> x to y } }.toSet()
        val ring = cells(r0..r0, r0..r1) + cells(r1..r1, r0..r1) + cells(r0..r1, r0..r0) + cells(r0..r1, r1..r1)
        // the plaza's approaches: from the ring to the stadium's lawn
        val approaches = cells(a0..a1, r0 + 1 until py - 1) + cells(a0..a1, py + pitch.height + 1 until r1) +
            cells(r0 + 1 until px - 1, a0..a1) + cells(px + pitch.width + 1 until r1, a0..a1)
        val boulevards = cells(a0..a1, lo until r0) + cells(a0..a1, r1 + 1 until hi) + cells(lo until r0, a0..a1) + cells(r1 + 1 until hi, a0..a1)
        val avenues = listOf(r0, r1).flatMap { r -> cells(r..r, lo until r0) + cells(r..r, r1 + 1 until hi) + cells(lo until r0, r..r) + cells(r1 + 1 until hi, r..r) }.toSet()
        val allowance = BuildingCatalog.spec(BuildingType.ROAD).countAt(stadium)
        val roads = LinkedHashSet<Pair<Int, Int>>()
        for (part in listOf(ring, approaches, boulevards, avenues)) if (roads.size + (part - roads).size <= allowance) roads += part

        // the blocks between the lines, a pavement cell off every side
        val spans = listOf(lo until r0, r0 + 1 until a0, a1 + 1 until r1, r1 + 1 until hi)
        fun lot(xs: IntRange, ys: IntRange) = Lot(xs.first + 1, xs.last, ys.first + 1, ys.last)
        // the plaza: four quadrants round the stadium, each a lot facing the stands and a smaller one by the stadium's end
        val quadrants = listOf(1 to 1, 2 to 1, 2 to 2, 1 to 2)
        val front = quadrants.map { (i, j) ->
            val q = lot(spans[i], spans[j])
            if (j == 1) Lot(q.x0, q.x1, q.y0, py - 2) else Lot(q.x0, q.x1, py + pitch.height + 2, q.y1)
        }
        val ends = quadrants.map { (i, j) ->
            val q = lot(spans[i], spans[j])
            Lot(if (i == 1) q.x0 else px + pitch.width + 1, if (i == 1) px - 1 else q.x1, if (j == 1) py - 1 else q.y0, if (j == 1) q.y1 else py + pitch.height + 1)
        }
        // the blocks round the plaza going clockwise from the top of the plot, every other one first (a pinwheel), then the corners
        val round = listOf(1 to 0, 2 to 0, 3 to 1, 3 to 2, 2 to 3, 1 to 3, 0 to 2, 0 to 1).map { (i, j) -> lot(spans[i], spans[j]) }
        val beside = round.filterIndexed { k, _ -> k % 2 == 0 } + round.filterIndexed { k, _ -> k % 2 == 1 }
        val corners = listOf(0 to 0, 3 to 0, 3 to 3, 0 to 3).map { (i, j) -> lot(spans[i], spans[j]) }

        // the queues: copies of a pattern in turn while the stadium allows them
        val left = BuildingType.values().associateWith { BuildingCatalog.spec(it).countAt(stadium) }.toMutableMap()
        fun turns(pattern: List<BuildingType>): MutableList<BuildingType> {
            val out = mutableListOf<BuildingType>()
            while (pattern.any { left.getValue(it) > 0 }) for (t in pattern) if (left.getValue(t) > 0) { out += t; left[t] = left.getValue(t) - 1 }
            return out
        }
        val plaza = turns(PLAZA)
        deal(front, plaza)
        val courts = turns(COURTS).apply { addAll(plaza) }
        deal(ends, courts)
        // the campus takes as few blocks as hold two each, the town the other blocks beside the plaza, then the corners
        val campus = turns(CAMPUS).apply { addAll(courts) }
        val campusLots = beside.take(minOf(beside.size, (campus.size + 1) / 2))
        deal(campusLots, campus)
        val town = (campus + turns(TOWERS) + turns(HOMES)).toMutableList()
        deal(beside.drop(campusLots.size), town)
        deal(corners, town)

        var houseFlip = 0
        fun level(t: BuildingType): Int {
            val cap = BuildingCatalog.spec(t).capAt(stadium)
            // houses and towers alternate a level so the town shows different facades
            return if (t == BuildingType.HOUSE || t == BuildingType.APARTMENT) (cap - (houseFlip++ % 2)).coerceAtLeast(1) else cap
        }
        val slots = mutableListOf(Slot(BuildingType.PITCH, stadium, px, py))
        for (lot in front + ends + beside + corners) arrange(lot, lot.types)?.forEachIndexed { k, (x, y) -> slots += Slot(lot.types[k], level(lot.types[k]), x, y) }
        val solid = HashSet<Pair<Int, Int>>()
        for (s in slots) { val sp = BuildingCatalog.spec(s.type); for (i in s.x until s.x + sp.width) for (j in s.y until s.y + sp.height) solid += i to j }
        val pitchCells = cells(px until px + pitch.width, py until py + pitch.height)

        // street trees on the pavement (never on a block's lawn — a lot leaves the pavement out), every third cell: along
        // the axes from the stadium to the bridges first (the approaches and boulevards laid), then the ring and the
        // avenues, nearest the stadium first; then the plot's edge, where the town meets the island's shore
        val decor = BuildingCatalog.spec(BuildingType.DECOR)
        var trees = decor.countAt(stadium)
        fun inPlot(p: Pair<Int, Int>) = p.first in lo until hi && p.second in lo until hi
        fun near(p: Pair<Int, Int>) = Math.hypot(p.first + 0.5 - c, p.second + 0.5 - c)
        val kerbs = listOf(approaches + boulevards, ring, avenues).flatMap { part ->
            roads.filter { it in part }.flatMap { (x, y) -> listOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1) }.filter { inPlot(it) && it !in roads }.distinct().sortedBy(::near)
        }
        val edge = (lo until hi).flatMap { i -> listOf(i to lo, i to hi - 1, lo to i, hi - 1 to i) }.filter { it !in roads }.distinct().sortedBy(::near)
        val planted = HashSet<Pair<Int, Int>>()
        for (p in kerbs + edge) {
            if (trees <= 0) break
            if (Math.floorMod(p.first + p.second, 3) != 0 || p in planted) continue
            if (p in solid || (p.first - 1..p.first + 1).any { i -> (p.second - 1..p.second + 1).any { j -> (i to j) in pitchCells } }) continue
            planted += p; slots += Slot(BuildingType.DECOR, decor.capAt(stadium), p.first, p.second); trees--
        }
        val roadLevel = BuildingCatalog.spec(BuildingType.ROAD).capAt(stadium).coerceAtLeast(1)
        for ((x, y) in roads) slots += Slot(BuildingType.ROAD, roadLevel, x, y)
        return City(stadium, Land.HOME, slots)
    }
}
