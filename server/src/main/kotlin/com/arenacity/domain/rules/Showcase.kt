package com.arenacity.domain.rules

import com.arenacity.domain.model.People
import kotlin.math.abs

/**
 * Showcase cities (the owner, 12 October: «every player gets two friends whose cities are built — one with a level-5
 * stadium, one built to the maximum — so players see what their city can become»; then «a beautiful city, not buildings
 * on top of each other»). Two bot clubs shared by everyone, on the land mask ([Lands]; the owner's picture, 13 October:
 * the land follows the painted meadow, an organic shape), laid out as a planned town round the stadium on the main
 * island, the peoples' islands open at that stadium (15 October: no arenas on them any more, a people lives on each):
 *  - the stadium, the town's biggest building, at the mask's anchor, the meadow's middle, on its plaza; a ring avenue
 *    round the plaza, boulevards one tile (20 m) wide on the axes from the stadium out to the shore (towards the side islands; a bridge's foot off them gets
 *    a short spur, straight or with one turn) and the ring's lines run on to the shore, with another avenue every 16 cells where the meadow reaches
 *    far beyond the ring: blocks of 15 cells between the lines. Only cells on the meadow are streets, and only those that
 *    join the ring. The road allowance lays the ring, the plaza's approaches, the boulevards with the spurs and the
 *    avenues in that order (stadium 5: the ring; from stadium 8 the whole grid); a line not laid yet stays lawn;
 *  - every block keeps a lawn tile along the shore (on tiles its buildings front the streets: a 20 m pavement took too much
 *    of the meadow); what is left of it
 *    on the meadow holds one lot, its largest rectangle (a block the shore cuts below 3×2 stays meadow); the lot's
 *    buildings stand in rows with a lawn cell at least between them, the spare lawn spread evenly over the lot;
 *  - the club round the stadium: the hall of fame, ticket offices, shopping centres and the fan zone on the plaza facing
 *    the stands, the media centre and the sponsors at its ends, the campus (training, academy, medical, recovery, the
 *    athletes' residence, the science lab, the youth school, the coaches, the analysts, the scouts) in the blocks next to
 *    it (every other block going round); the
 *    town outwards — the hotel, the school and the transport hub in the other blocks along the boulevards, houses and
 *    yard courts in the outer blocks once those are full;
 *  - trees along the boulevards and the plaza's approaches first, then the ring, the avenues and the shore;
 *  - the peoples' islands whose bridge the stadium allows are open ([islands]); the town builds nothing there (the
 *    client draws each people's settlement in the middle).
 * Within the game's limits (no type over its count or level cap at that stadium); the town takes as many copies as fit
 * with that spacing (at the top fewer houses and yard courts than allowed, so it stays spacious). Pure: the same mask and
 * catalogue always give the same city.
 *
 * Laid out on the build grid's tiles (14 October, pass 3: [Grid.TILE] = 2×2 cells, a tile of the meadow only where all its
 * cells are, [LandRegion.tiles]): every distance below — the ring, the blocks, the pavement, the lawn between buildings,
 * a street, a tree — is in tiles, and the slots come out in cells, every one on a tile.
 */
object Showcase {
    data class Slot(val type: BuildingType, val level: Int, val x: Int, val y: Int)
    /** [regions]: the land mask's regions the town stands on (the main island and the open side islands); [zones]: the zones open. */
    data class City(val stadium: Int, val regions: Set<String>, val slots: List<Slot>, val zones: Set<People> = emptySet())

    /**
     * Tiles from the stadium's middle to the ring's lines: c − 8 and c + 7 (tiles 0 and 15 round the 6×6-tile stadium's
     * middle c = (8, 8) at the anchor cell (10, 10); the plaza's lots between the ring and the stands, a lawn tile off the
     * stadium, are 3 tiles deep: the 3×3-tile hall of fame and shopping centre fit; a ring further out left the outer
     * blocks too thin for the campus on the 26 × 33-tile meadow). Its 8·RING − 4 = 60 tiles are within the stadium-5 road
     * allowance (BuildingCatalog: 80).
     */
    internal const val RING = 8
    /** Tiles between two parallel lines: a 9-tile block (180 m: a 20 m road takes twice a 10 m one's meadow). */
    private const val BLOCK = 9
    /** Another avenue beyond the ring only where the island reaches at least this many tiles past it. */
    private const val OUTER = 5
    /** Lots a block holds at most: its largest rectangle and what the shore leaves beside it. */
    private const val LOTS = 3
    /** A bridge's foot off the streets is joined to the nearest one by a spur (straight, or with one turn) at most this many tiles. */
    private const val SPUR = 6
    private const val T = Grid.TILE

    /** A building's footprint in tiles (w, h): every footprint is whole tiles since 14 October, pass 3. */
    private fun size(type: BuildingType) = BuildingCatalog.spec(type).let { it.width / T to it.height / T }

    /** The stadium's own trade on the plaza, facing the stands: the hall of fame, then the earners in turn. */
    private val PLAZA = listOf(BuildingType.HALL_OF_FAME, BuildingType.TICKET_OFFICE, BuildingType.SHOPPING_CENTER, BuildingType.FAN_ZONE)
    /** The press and the sponsors at the stadium's ends. */
    private val ENDS = listOf(BuildingType.MEDIA_CENTER, BuildingType.SPONSOR_OFFICE)
    /** The club's campus in the blocks next to the plaza. */
    private val CAMPUS = listOf(BuildingType.TRAINING_CENTER, BuildingType.ACADEMY, BuildingType.MEDICAL_CENTER, BuildingType.RECOVERY_CENTER,
        BuildingType.ATHLETES_RESIDENCE, BuildingType.SPORTS_SCIENCE_LAB, BuildingType.YOUTH_SPORTS_SCHOOL, BuildingType.COACHING_HQ,
        BuildingType.ANALYTICS_CENTER, BuildingType.SCOUTING_OFFICE)
    /** Downtown along the boulevards: the hotel, the school and the transport hub in turn. */
    private val DOWNTOWN = listOf(BuildingType.HOTEL, BuildingType.SCHOOL, BuildingType.TRANSPORT_HUB)
    /** The suburbs: a house, a yard court, a house (the court second: a small meadow's last lot still gets one). */
    private val HOMES = listOf(BuildingType.HOUSE, BuildingType.BACKYARD_COURT, BuildingType.HOUSE)

    /** The peoples' islands open in a showcase at [stadium]: those whose bridge that stadium allows (rules.Zones). */
    fun islands(stadium: Int, lands: LandMap = Lands.MAP): List<LandRegion> =
        lands.regions.filter { r -> r !== lands.main && r.people?.let { Zones.spec(it).requiredStadium <= stadium } == true }

    /**
     * The cells [x0, x1) × [y0, y1) a block's buildings stand on (its pavement already left out) and what stands there;
     * [highX]/[highY]: the stadium lies towards the lot's high x / y side (the first building goes in that corner).
     */
    private class Lot(val x0: Int, val x1: Int, val y0: Int, val y1: Int, cx: Int, cy: Int) {
        val highX = (x0 + x1) / 2.0 < cx; val highY = (y0 + y1) / 2.0 < cy
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
        val sizes = types.map { size(it) }
        val along = sizes.map { if (alongX) it.first else it.second }
        val across = sizes.map { if (alongX) it.second else it.first }
        val rows = mutableListOf<MutableList<Int>>()
        for (i in types.indices) {
            if (along[i] > len) return null
            val row = rows.firstOrNull { r -> r.sumOf { along[it] } + r.size + along[i] <= len }
            if (row != null) row += i else rows += mutableListOf(i)
        }
        val depths = rows.map { r -> r.maxOf { across[it] } }
        val rowAt = spread(deep - depths.sum(), depths) ?: return null
        val at = arrayOfNulls<Pair<Int, Int>>(types.size)
        for ((k, r) in rows.withIndex()) {
            val starts = spread(len - r.sumOf { along[it] }, r.map { along[it] }) ?: return null
            for ((j, i) in r.withIndex()) {
                val a = starts[j]; val b = rowAt[k] + (depths[k] - across[i]) / 2
                // from the lot's corner nearest the stadium: u along x, v along y
                val u = if (alongX) a else b; val v = if (alongX) b else a
                val x = if (lot.highX) lot.x1 - u - sizes[i].first else lot.x0 + u
                val y = if (lot.highY) lot.y1 - v - sizes[i].second else lot.y0 + v
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

    /**
     * The largest rectangle [x0, x1) × [y0, y1) whose every cell is [ok] (the first found on a tie), as (x0, x1, y0, y1);
     * null when it is smaller than 2×2 (tiles).
     */
    private fun largest(x0: Int, x1: Int, y0: Int, y1: Int, ok: (Int, Int) -> Boolean): IntArray? {
        val w = x1 - x0
        if (w <= 0 || y1 <= y0) return null
        val up = IntArray(w)
        var best: IntArray? = null; var area = 0
        for (y in y0 until y1) {
            for (i in 0 until w) up[i] = if (ok(x0 + i, y)) up[i] + 1 else 0
            // the largest rectangle standing on row y: a stack of columns with rising heights
            val stack = ArrayDeque<Int>()
            for (i in 0..w) {
                val h = if (i < w) up[i] else 0
                while (stack.isNotEmpty() && up[stack.last()] >= h) {
                    val height = up[stack.removeLast()]
                    val left = if (stack.isEmpty()) 0 else stack.last() + 1
                    if (height * (i - left) > area) { area = height * (i - left); best = intArrayOf(x0 + left, x0 + i, y - height + 1, y + 1) }
                }
                stack.addLast(i)
            }
        }
        val b = best ?: return null
        val bw = b[1] - b[0]; val bh = b[3] - b[2]
        return b.takeIf { bw >= 2 && bh >= 2 }
    }

    /** The lines along one axis through the stadium's middle [c]: the ring's, the boulevard's pair, and avenues beyond the ring while the island [lo, hi) reaches far enough. */
    private fun lines(c: Int, lo: Int, hi: Int): List<Int> {
        val out = mutableListOf(c - RING, c, c + RING - 1)
        var l = c - RING - (BLOCK + 1)
        while (l - lo >= OUTER) { out += l; l -= BLOCK + 1 }
        l = c + RING - 1 + BLOCK + 1
        while (hi - 1 - l >= OUTER) { out += l; l += BLOCK + 1 }
        return out.sorted()
    }

    /** The stretches of cells strictly between consecutive [lines] (the island's edges [lo, hi) closing the outer ones). */
    private fun spans(lines: List<Int>, lo: Int, hi: Int): List<IntRange> =
        (listOf(lo - 1) + lines + listOf(hi)).zipWithNext().map { (a, b) -> a + 1 until b }.filter { !it.isEmpty() }

    fun city(stadium: Int, lands: LandMap = Lands.MAP): City {
        // in tiles from here on (the meadow's whole tiles), the slots in cells at the end
        val land = lands.main.tiles()
        val (pw, ph) = size(BuildingType.PITCH)
        val px = Math.floorDiv(lands.stadium.first, T); val py = Math.floorDiv(lands.stadium.second, T)
        // the stadium's middle: the tile right of and below it (8, 8 for the anchor cell 10, 10)
        val cx = px + pw / 2; val cy = py + ph / 2
        val bx0 = land.x0; val bx1 = land.x0 + land.w; val by0 = land.y0; val by1 = land.y0 + land.h
        fun on(p: Pair<Int, Int>) = land.has(p.first, p.second)
        fun cells(xs: IntRange, ys: IntRange) = xs.flatMap { x -> ys.map { y -> x to y } }.toSet()
        fun around(p: Pair<Int, Int>) = listOf(p.first + 1 to p.second, p.first - 1 to p.second, p.first to p.second + 1, p.first to p.second - 1)

        // the street plan: the ring (c − RING .. c + RING − 1), the boulevards on the axes (c), the avenues
        val xs = lines(cx, bx0, bx1); val ys = lines(cy, by0, by1)
        val xr0 = cx - RING; val xr1 = cx + RING - 1; val yr0 = cy - RING; val yr1 = cy + RING - 1
        val ring = cells(xr0..xr0, yr0..yr1) + cells(xr1..xr1, yr0..yr1) + cells(xr0..xr1, yr0..yr0) + cells(xr0..xr1, yr1..yr1)
        // the plaza's approaches: from the ring to the stadium's lawn
        val approaches = cells(cx..cx, yr0 + 1 until py - 1) + cells(cx..cx, py + ph + 1 until yr1) +
            cells(xr0 + 1 until px - 1, cy..cy) + cells(px + pw + 1 until xr1, cy..cy)
        val boulevards = cells(cx..cx, by0 until yr0) + cells(cx..cx, yr1 + 1 until by1) + cells(bx0 until xr0, cy..cy) + cells(xr1 + 1 until bx1, cy..cy)
        val avenues = xs.filter { it <= xr0 || it >= xr1 }.flatMap { x -> if (x == xr0 || x == xr1) cells(x..x, by0 until yr0) + cells(x..x, yr1 + 1 until by1) else cells(x..x, by0 until by1) } +
            ys.filter { it <= yr0 || it >= yr1 }.flatMap { y -> if (y == yr0 || y == yr1) cells(bx0 until xr0, y..y) + cells(xr1 + 1 until bx1, y..y) else cells(bx0 until bx1, y..y) }
        // a street is a cell on the meadow joined to the ring through other streets (a line the shore cuts off is no street)
        val planned = (ring + approaches + boulevards + avenues).filter(::on).toSet()
        fun joined(plan: Set<Pair<Int, Int>>): Set<Pair<Int, Int>> {
            val seen = HashSet<Pair<Int, Int>>(); val queue = ArrayDeque(ring.filter { it in plan })
            seen += queue
            while (queue.isNotEmpty()) for (n in around(queue.removeFirst())) if (n in plan && seen.add(n)) queue.addLast(n)
            return seen
        }
        val streets = joined(planned)
        // a bridge's foot (where the roads meet its crossing on the meadow) off the streets gets a spur to the nearest one:
        // a straight run or one with a single turn, on the meadow, at most SPUR cells (else the boulevard on its side is
        // the way) — to the ring, an approach or a boulevard first (laid at every stadium the roads reach that far), an
        // avenue (laid last, when the allowance has room) only when none of those is within SPUR
        val spurs = LinkedHashSet<Pair<Int, Int>>()
        val core = (ring + approaches + boulevards).toSet()
        fun run(a: Int, b: Int) = if (a <= b) a..b else a downTo b
        for ((_, foot) in lands.bridges.entries.sortedBy { it.key }) {
            val b = Math.floorDiv(foot.first, T) to Math.floorDiv(foot.second, T)
            if (!on(b) || b in streets) continue
            var best: List<Pair<Int, Int>>? = null
            fun dist(s: Pair<Int, Int>) = abs(s.first - b.first) + abs(s.second - b.second)
            val near = streets.filter { dist(it) <= SPUR }
            for (s in (near.filter { it in core }.ifEmpty { near }).sortedWith(compareBy({ dist(it) }, { it.first }, { it.second }))) {
                for (alongX in listOf(true, false)) {
                    // from the foot to the street's cell (left out: a street already), x first or y first
                    val path = if (alongX) run(b.first, s.first).map { it to b.second } + run(b.second, s.second).drop(1).map { s.first to it }
                               else run(b.second, s.second).map { b.first to it } + run(b.first, s.first).drop(1).map { it to s.second }
                    val spur = path.dropLast(1)
                    if (spur.all { on(it) && it !in streets } && (best == null || spur.size < best.size)) best = spur
                }
            }
            best?.let { spurs += it }
        }

        val allowance = BuildingCatalog.spec(BuildingType.ROAD).countAt(stadium)
        val roads = LinkedHashSet<Pair<Int, Int>>()
        for (part in listOf(ring, approaches, boulevards + spurs, avenues)) {
            val add = part.filter { it in streets || it in spurs } - roads
            if (roads.size + add.size <= allowance) roads += add
        }

        // the lots: the blocks between the lines up to the streets, a lawn tile off every spur and the shore
        val nearSpur = spurs.flatMap { around(it) + it }.toSet()
        // a lot's tile: on the meadow with its four neighbours (a lawn tile off the shore along the axes), off the spurs
        fun lotCell(x: Int, y: Int) = land.has(x, y) && around(x to y).all { land.has(it.first, it.second) } && (x to y) !in nearSpur
        fun lot(x0: Int, x1: Int, y0: Int, y1: Int): Lot? = largest(x0, x1, y0, y1, ::lotCell)?.let { Lot(it[0], it[1], it[2], it[3], cx, cy) }
        /**
         * A block's lots: its largest rectangle on the meadow, then — where the shore cuts the block into an odd shape —
         * the largest of what is left (a lawn cell off the lots before), while one of 3×3 or more is left.
         */
        fun block(sx: IntRange, sy: IntRange): List<Lot> {
            val x0 = sx.first; val x1 = sx.last + 1; val y0 = sy.first; val y1 = sy.last + 1   // up to the streets (tiles: no pavement)
            val out = mutableListOf<Lot>()
            while (out.size < LOTS) {
                val r = largest(x0, x1, y0, y1) { x, y -> lotCell(x, y) && out.none { x >= it.x0 - 1 && x <= it.x1 && y >= it.y0 - 1 && y <= it.y1 } } ?: break
                if (out.isNotEmpty() && (r[1] - r[0] < 2 || r[3] - r[2] < 2)) break
                out += Lot(r[0], r[1], r[2], r[3], cx, cy)
            }
            return out
        }
        val sx = spans(xs, bx0, bx1); val sy = spans(ys, by0, by1)
        val plazaX = sx.filter { it.first > xr0 && it.last < xr1 }; val plazaY = sy.filter { it.first > yr0 && it.last < yr1 }
        // the plaza: four quadrants round the stadium, each a lot facing the stands and a smaller one by the stadium's end
        val quadrants = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1)
        val front = quadrants.mapNotNull { (i, j) ->
            val qx = plazaX[i]; val qy = plazaY[j]
            if (j == 0) lot(qx.first, qx.last + 1, qy.first, py - 1) else lot(qx.first, qx.last + 1, py + ph + 1, qy.last + 1)
        }
        val ends = quadrants.mapNotNull { (i, j) ->
            val qx = plazaX[i]; val qy = plazaY[j]
            lot(if (i == 0) qx.first else px + pw + 1, if (i == 0) px - 1 else qx.last + 1,
                if (j == 0) py else qy.first, if (j == 0) qy.last + 1 else py + ph)   // a lawn tile off the front lots
        }
        // the blocks round the plaza going clockwise from the top, every other one first (a pinwheel), then the outer ones
        // ring by ring, each ring clockwise from the top-left corner
        val px0 = sx.indexOf(plazaX.first()); val px1 = sx.indexOf(plazaX.last()); val py0 = sy.indexOf(plazaY.first()); val py1 = sy.indexOf(plazaY.last())
        fun angle(i: Int, j: Int): Double {
            val dx = (sx[i].first + sx[i].last) / 2.0 - cx; val dy = (sy[j].first + sy[j].last) / 2.0 - cy
            return (Math.atan2(dx, -dy) + Math.PI / 4 + 2 * Math.PI) % (2 * Math.PI)
        }
        fun ringOf(i: Int, j: Int) = maxOf(if (i < px0) px0 - i else if (i > px1) i - px1 else 0, if (j < py0) py0 - j else if (j > py1) j - py1 else 0)
        val blocks = sx.indices.flatMap { i -> sy.indices.map { j -> i to j } }.filter { (i, j) -> ringOf(i, j) > 0 }
        val round = blocks.filter { (i, j) -> ringOf(i, j) == 1 && (i in px0..px1 || j in py0..py1) }.sortedBy { (i, j) -> angle(i, j) }
        val roundLots = (round.filterIndexed { k, _ -> k % 2 == 0 } + round.filterIndexed { k, _ -> k % 2 == 1 }).map { (i, j) -> block(sx[i], sy[j]) }
        val beside = roundLots.mapNotNull { it.firstOrNull() }
        // the outer blocks, ring by ring, and the odd corners the shore leaves of the blocks beside the plaza
        val outer = (blocks - round.toSet()).sortedWith(compareBy<Pair<Int, Int>>({ (i, j) -> ringOf(i, j) }, { (i, j) -> angle(i, j) })).flatMap { (i, j) -> block(sx[i], sy[j]) } +
            roundLots.flatMap { it.drop(1) }

        // the queues: copies of a pattern in turn while the stadium allows them
        val left = BuildingType.values().associateWith { BuildingCatalog.spec(it).countAt(stadium) }.toMutableMap()
        fun turns(pattern: List<BuildingType>): MutableList<BuildingType> {
            val out = mutableListOf<BuildingType>()
            while (pattern.any { left.getValue(it) > 0 }) for (t in pattern) if (left.getValue(t) > 0) { out += t; left[t] = left.getValue(t) - 1 }
            return out
        }
        val plaza = turns(PLAZA)
        deal(front, plaza)
        val press = turns(ENDS).apply { addAll(plaza) }
        deal(ends, press)
        // the campus takes as few blocks as hold two each, the town the other blocks beside the plaza, then the outer ones
        val campus = turns(CAMPUS).apply { addAll(press) }
        val campusLots = beside.take(minOf(beside.size, (campus.size + 1) / 2))
        deal(campusLots, campus)
        val town = (campus + turns(DOWNTOWN) + turns(HOMES)).toMutableList()
        deal(beside.drop(campusLots.size), town)
        deal(outer, town)
        // and where the campus left room, then the plaza (the grown footprints of 14 October fill a smaller meadow's blocks
        // before the town's first house: the plaza's lots keep room beside the hall of fame and the fan zone)
        deal(campusLots, town)
        deal(front + ends, town)

        var houseFlip = 0
        fun level(t: BuildingType): Int {
            val cap = BuildingCatalog.spec(t).capAt(stadium)
            // houses alternate a level so the town shows different facades
            return if (t == BuildingType.HOUSE) (cap - (houseFlip++ % 2)).coerceAtLeast(1) else cap
        }
        val slots = mutableListOf(Slot(BuildingType.PITCH, stadium, px, py))
        for (lot in front + ends + beside + outer) arrange(lot, lot.types)?.forEachIndexed { k, (x, y) -> slots += Slot(lot.types[k], level(lot.types[k]), x, y) }
        val solid = HashSet<Pair<Int, Int>>()
        for (s in slots) { val (sw, sh) = size(s.type); for (i in s.x until s.x + sw) for (j in s.y until s.y + sh) solid += i to j }
        val pitchCells = cells(px until px + pw, py until py + ph)

        // street trees on the kerbs left free (never on a building), every third tile: along the axes
        // from the stadium towards the bridges first (the approaches, boulevards and spurs laid), then the ring and the
        // avenues, nearest the stadium first; then the shore, where the town meets the beach
        val decor = BuildingCatalog.spec(BuildingType.DECOR)
        var trees = decor.countAt(stadium)
        fun near(p: Pair<Int, Int>) = Math.hypot(p.first + 0.5 - cx, p.second + 0.5 - cy)
        val kerbs = listOf(approaches + boulevards + spurs, ring, avenues).flatMap { part ->
            roads.filter { it in part }.flatMap(::around).filter { on(it) && it !in roads }.distinct().sortedBy(::near)
        }
        val shore = land.cells.filter { p -> around(p).any { !on(it) } && p !in roads }.sortedBy(::near)
        val planted = HashSet<Pair<Int, Int>>()
        for (p in kerbs + shore) {
            if (trees <= 0) break
            if (Math.floorMod(p.first + p.second, 3) != 0 || p in planted) continue
            if (p in solid || (p.first - 1..p.first + 1).any { i -> (p.second - 1..p.second + 1).any { j -> (i to j) in pitchCells } }) continue
            planted += p; slots += Slot(BuildingType.DECOR, decor.capAt(stadium), p.first, p.second); trees--
        }
        val roadLevel = BuildingCatalog.spec(BuildingType.ROAD).capAt(stadium).coerceAtLeast(1)
        for ((x, y) in roads) slots += Slot(BuildingType.ROAD, roadLevel, x, y)

        // the peoples' islands open at this stadium (nothing built there: the settlements are the client's)
        val open = islands(stadium, lands)
        val ground = LandSet(listOf(lands.main) + open)
        val cells = slots.map { it.copy(x = it.x * T, y = it.y * T) }
        check(cells.all { s -> Grid.aligned(s.x, s.y) && lands.allows(ground, s.type, footprint(s.type, s.x, s.y, 0)) }) { "showcase: a slot off its island" }
        return City(stadium, ground.ids.toSet(), cells, open.mapNotNull { it.people }.toSet())
    }
}
