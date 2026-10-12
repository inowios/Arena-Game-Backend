package com.arenacity.domain.rules

/**
 * Showcase cities (the owner, 12 October: «every player gets two friends whose cities are built — one with a level-5
 * stadium, one built to the maximum — so players see what their city can become»; then «a beautiful city, not buildings
 * on top of each other»). Two bot clubs shared by everyone, laid out like a planned town: avenues on the parcel edges,
 * every parcel a block with one or two buildings on their own lawns, the stadium on its own plaza (four parcels without
 * inner avenues at the top), club buildings round it, the town outwards with offices, towers, parks and houses mixed,
 * trees along the kerbs. Within the game's limits (no type over its count or level cap at that stadium); fewer houses
 * than allowed at the top so the skyline stays varied. Pure: the same input, the same city.
 */
object Showcase {
    data class Slot(val type: BuildingType, val level: Int, val x: Int, val y: Int)
    data class City(val stadium: Int, val parcels: Set<Pair<Int, Int>>, val slots: List<Slot>)

    /** The land each showcase owns: the 3×3 parcels round home for stadium 5, the whole 6×6 field at the top. */
    fun parcels(stadium: Int): Set<Pair<Int, Int>> =
        if (stadium >= Stadium.MAX_LEVEL) (0 until Land.PARCELS).flatMap { c -> (0 until Land.PARCELS).map { r -> c to r } }.toSet()
        else (1..3).flatMap { c -> (1..3).map { r -> c to r } }.toSet()

    /** The stadium district: the four home parcels merged into one plaza at the top, the centre parcel at stadium 5. */
    private fun district(stadium: Int): Set<Pair<Int, Int>> = if (stadium >= Stadium.MAX_LEVEL) Land.HOME else setOf(2 to 2)

    private val CLUB = listOf(BuildingType.ACADEMY, BuildingType.TRAINING_CENTER, BuildingType.MEDICAL_CENTER, BuildingType.BASKETBALL_COURT,
        BuildingType.TENNIS_COURT, BuildingType.CAFE, BuildingType.PARKING, BuildingType.CLUB_SHOP, BuildingType.TICKET_OFFICE)
    /** The town, interleaved so neighbouring blocks differ. */
    private val TOWN_PATTERN = listOf(BuildingType.OFFICE, BuildingType.HOUSE, BuildingType.APARTMENT, BuildingType.PARK, BuildingType.HOUSE,
        BuildingType.APARTMENT, BuildingType.OFFICE, BuildingType.HOUSE, BuildingType.PARK)

    private fun count(type: BuildingType, stadium: Int): Int {
        val n = BuildingCatalog.spec(type).countAt(stadium)
        return if (stadium >= Stadium.MAX_LEVEL) when (type) {   // a varied skyline rather than a carpet of the same house
            BuildingType.HOUSE -> minOf(n, 12); BuildingType.PARK -> minOf(n, 8); BuildingType.APARTMENT -> minOf(n, 9); else -> n
        } else n
    }

    fun city(stadium: Int): City {
        val parcels = parcels(stadium)
        val center = district(stadium)
        fun parcelOf(x: Int, y: Int): Pair<Int, Int>? {
            if (x < Land.ORIGIN || y < Land.ORIGIN) return null
            val p = Land.parcelOf(x, y); return p.takeIf { Land.inField(it.first, it.second) && it in parcels }
        }
        // avenues: a parcel's west and north edges when there is owned land on the other side, except inside the district
        val roads = HashSet<Pair<Int, Int>>()
        for ((c, r) in parcels) {
            val x0 = Land.cell(c); val y0 = Land.cell(r)
            if ((c - 1 to r) in parcels && !((c to r) in center && (c - 1 to r) in center)) for (y in y0 until y0 + Land.PARCEL) roads += x0 to y
            if ((c to r - 1) in parcels && !((c to r) in center && (c to r - 1) in center)) for (x in x0 until x0 + Land.PARCEL) roads += x to y0
        }
        // close the crossings so the grid has no gaps
        for ((c, r) in parcels) { val p = Land.cell(c) to Land.cell(r); if ((c - 1 to r - 1) in parcels && ((c - 1 to r) in parcels || (c to r - 1) in parcels) && !(((c to r) in center) && (c - 1 to r - 1) in center)) roads += p }

        val taken = HashSet<Pair<Int, Int>>(roads)
        val slots = mutableListOf<Slot>()
        val buildingCells = HashSet<Pair<Int, Int>>()

        /** Cells of a block: the parcels' cells off the avenues. */
        class Block(val lots: Set<Pair<Int, Int>>) {
            val x0 = lots.minOf { Land.cell(it.first) }; val x1 = lots.maxOf { Land.cell(it.first) + Land.PARCEL }
            val y0 = lots.minOf { Land.cell(it.second) }; val y1 = lots.maxOf { Land.cell(it.second) + Land.PARCEL }
            val cx = (x0 + x1) / 2.0; val cy = (y0 + y1) / 2.0
        }
        fun fits(b: Block, x: Int, y: Int, w: Int, h: Int, gap: Int): Boolean {
            for (i in x until x + w) for (j in y until y + h) { if (parcelOf(i, j) !in b.lots || (i to j) in taken) return false }
            for (i in x - 1 until x + w + 1) for (j in y - 1 until y + h + 1) if ((i to j) in roads && (i !in x until x + w || j !in y until y + h)) {
                // a building may front an avenue but keeps a pavement cell — except where the block is tight
                if (gap > 1) return false
            }
            for (i in x - gap until x + w + gap) for (j in y - gap until y + h + gap) if ((i to j) in buildingCells) return false
            return true
        }
        fun put(b: Block, type: BuildingType, level: Int, prefer: (Int, Int, Int, Int) -> Double, gap: Int = 2): Boolean {
            val s = BuildingCatalog.spec(type)
            val spots = ArrayList<Triple<Int, Int, Double>>()
            for (x in b.x0..b.x1 - s.width) for (y in b.y0..b.y1 - s.height) spots += Triple(x, y, prefer(x, y, s.width, s.height))
            spots.sortBy { it.third }
            for (g in listOf(gap, 1)) for ((x, y, _) in spots) if (fits(b, x, y, s.width, s.height, g)) {
                for (i in x until x + s.width) for (j in y until y + s.height) { taken += i to j; buildingCells += i to j }
                slots += Slot(type, level, x, y); return true
            }
            return false
        }
        val cap = { t: BuildingType -> BuildingCatalog.spec(t).capAt(stadium) }
        fun centred(b: Block) = { x: Int, y: Int, w: Int, h: Int -> Math.hypot(x + w / 2.0 - b.cx, y + h / 2.0 - b.cy) }

        // the stadium district
        val hub = Block(center)
        put(hub, BuildingType.PITCH, stadium, centred(hub), gap = 3)
        val clubQueue = ArrayDeque<BuildingType>()
        if (stadium >= Stadium.MAX_LEVEL) {
            // the plaza holds the museum, the ticket offices and the shop round the stadium
            // north, south, west and east of the stadium (in screen terms: up, down, left, right)
            val around = listOf(BuildingType.MUSEUM to (-8.0 to -8.0), BuildingType.TICKET_OFFICE to (8.0 to 8.0),
                BuildingType.TICKET_OFFICE to (-8.5 to 8.5), BuildingType.CLUB_SHOP to (8.5 to -8.5))
            for ((t, d) in around) put(hub, t, cap(t), { x, y, w, h -> Math.hypot(x + w / 2.0 - (hub.cx + d.first), y + h / 2.0 - (hub.cy + d.second)) })
        } else if (count(BuildingType.MUSEUM, stadium) > 0) clubQueue += BuildingType.MUSEUM
        val placed = slots.groupingBy { it.type }.eachCount()
        for (t in CLUB) repeat(count(t, stadium) - (placed[t] ?: 0)) { clubQueue += t }
        val townQueue = ArrayDeque<BuildingType>()
        val left = TOWN_PATTERN.associateWith { 0 }.keys.associateWith { count(it, stadium) }.toMutableMap()
        while (left.values.any { it > 0 }) for (t in TOWN_PATTERN) if ((left[t] ?: 0) > 0) { townQueue += t; left[t] = left.getValue(t) - 1 }

        // the other blocks, nearest the stadium first: every block gets one building, the nearest get a second;
        // one stands in the middle of its lawn, two stand side by side across the screen (x − z is screen-horizontal)
        val blocks = (parcels - center).map { Block(setOf(it)) }.sortedBy { Math.hypot(it.cx - hub.cx, it.cy - hub.cy) }
        val items = (clubQueue + townQueue).toMutableList()
        val perBlock = blocks.associateWith { mutableListOf<BuildingType>() }
        var i = 0
        while (i < items.size) { for (bl in blocks) { if (i >= items.size) break; if (perBlock.getValue(bl).size < 2) perBlock.getValue(bl) += items[i++] }; if (blocks.all { perBlock.getValue(it).size >= 2 }) break }
        var houseFlip = 0
        fun level(t: BuildingType): Int {
            val c = cap(t)
            // houses and towers alternate a level so the modern town shows different facades
            return if (t == BuildingType.HOUSE || t == BuildingType.APARTMENT) (c - (houseFlip++ % 2)).coerceAtLeast(1) else c
        }
        fun near(tx: Double, ty: Double) = { x: Int, y: Int, w: Int, h: Int -> Math.hypot(x + w / 2.0 - tx, y + h / 2.0 - ty) }
        val leftover = mutableListOf<BuildingType>()
        for (bl in blocks) {
            val list = perBlock.getValue(bl)
            if (list.size == 1) { if (!put(bl, list[0], level(list[0]), centred(bl))) leftover += list[0] }
            else list.forEachIndexed { k, t ->
                val d = if (k == 0) -2.6 else 2.6     // west-south and east-north of the centre: left and right on screen
                if (!put(bl, t, level(t), near(bl.cx + d, bl.cy - d))) leftover += t
            }
        }
        for (t in leftover + items.drop(i)) blocks.firstOrNull { put(it, t, level(t), centred(it), gap = 1) }

        // trees along the avenues: every other kerb cell beside a road, up to the decor allowance, the inner ones first
        val decor = BuildingCatalog.spec(BuildingType.DECOR)
        var trees = decor.countAt(stadium)
        val kerbs = roads.flatMap { (rx, ry) -> listOf(rx + 1 to ry, rx - 1 to ry, rx to ry + 1, rx to ry - 1) }
            .filter { (x, y) -> parcelOf(x, y) != null && (x to y) !in taken && (x + y) % 2 == 0 }.distinct()
            .sortedBy { Math.hypot(it.first - hub.cx, it.second - hub.cy) }
        for (p in kerbs) {
            if (trees <= 0) break
            if ((p.first - 1..p.first + 1).any { i -> (p.second - 1..p.second + 1).any { j -> (i to j) in buildingCells } }) continue
            taken += p; slots += Slot(BuildingType.DECOR, decor.capAt(stadium), p.first, p.second); trees--
        }
        val roadLevel = BuildingCatalog.spec(BuildingType.ROAD).capAt(stadium).coerceAtLeast(1)
        for ((x, y) in roads) slots += Slot(BuildingType.ROAD, roadLevel, x, y)
        return City(stadium, parcels, slots)
    }
}
