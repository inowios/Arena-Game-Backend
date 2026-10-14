package com.arenacity.domain.rules

import com.arenacity.domain.model.ZoneSport
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.BitSet

/**
 * One region of the land mask: [w]×[h] cells from ([x0], [y0]) in the club's cell frame (cell (x, y) = world x in
 * [10x, 10x+10), z in [10y, 10y+10)); a building may stand on a set cell. [lock]: the zone sport whose arena opens the
 * region (a side island), null for the main island.
 */
class LandRegion(val id: String, val lock: String?, val x0: Int, val y0: Int, val w: Int, val h: Int, private val bits: BitSet) {
    /** [lock] as a zone sport; null for the main island, or for a lock this server does not know (such a region never opens). */
    val sport: ZoneSport? = lock?.let { l -> runCatching { ZoneSport.valueOf(l) }.getOrNull() }
    val area: Int = bits.cardinality()

    fun has(x: Int, y: Int): Boolean {
        val i = x - x0; val j = y - y0
        return i in 0 until w && j in 0 until h && bits[j * w + i]
    }

    /** Every cell of [fp] is set. */
    fun fits(fp: Footprint): Boolean {
        if (fp.x < x0 || fp.y < y0 || fp.x + fp.w > x0 + w || fp.y + fp.h > y0 + h) return false
        for (y in fp.y until fp.y + fp.h) for (x in fp.x until fp.x + fp.w) if (!bits[(y - y0) * w + x - x0]) return false
        return true
    }

    /** Every set cell, row by row. */
    val cells: List<Pair<Int, Int>> by lazy { (0 until h).flatMap { j -> (0 until w).filter { i -> bits[j * w + i] }.map { i -> x0 + i to y0 + j } } }

    /** The file's rows: '#' buildable, '.' not; row j is y = y0 + j, character i is x = x0 + i. */
    val rows: List<String> get() = (0 until h).map { j -> String(CharArray(w) { i -> if (bits[j * w + i]) '#' else '.' }) }

    fun overlapsBox(o: LandRegion) = x0 < o.x0 + o.w && o.x0 < x0 + w && y0 < o.y0 + o.h && o.y0 < y0 + h

    /**
     * This region in build-grid tiles of [t]×[t] cells (14 October, pass 3: [Grid.TILE]): tile (i, j) covers cells
     * [t·i, t·i + t) × [t·j, t·j + t) and is set when every one of them is (a tile is buildable only whole).
     */
    fun tiles(t: Int = Grid.TILE): LandRegion {
        val tx0 = Math.floorDiv(x0, t); val ty0 = Math.floorDiv(y0, t)
        val tw = Math.floorDiv(x0 + w - 1, t) - tx0 + 1; val th = Math.floorDiv(y0 + h - 1, t) - ty0 + 1
        val out = BitSet(tw * th)
        for (j in 0 until th) for (i in 0 until tw) {
            val cx = (tx0 + i) * t; val cy = (ty0 + j) * t
            if ((0 until t).all { a -> (0 until t).all { b -> has(cx + a, cy + b) } }) out.set(j * tw + i)
        }
        return LandRegion(id, lock, tx0, ty0, tw, th, out)
    }

    companion object {
        fun of(id: String, lock: String?, x0: Int, y0: Int, rows: List<String>): LandRegion {
            require(rows.isNotEmpty() && rows[0].isNotEmpty()) { "land mask: region $id is empty" }
            val w = rows[0].length
            val bits = BitSet(w * rows.size)
            for ((j, row) in rows.withIndex()) {
                require(row.length == w) { "land mask: region $id row $j is ${row.length} wide, not $w" }
                for ((i, ch) in row.withIndex()) when (ch) {
                    '#' -> bits.set(j * w + i)
                    '.' -> {}
                    else -> throw IllegalArgumentException("land mask: region $id row $j has '$ch'")
                }
            }
            return LandRegion(id, lock, x0, y0, w, rows.size, bits)
        }
    }
}

/** The land a club builds on now: the main island and the side islands whose arena opened them (the regions are sea apart). */
class LandSet(val regions: List<LandRegion>) {
    fun has(x: Int, y: Int) = regions.any { it.has(x, y) }
    /** A footprint stands on one region: islands are sea apart, nothing straddles two. */
    fun fits(fp: Footprint) = regions.any { it.fits(fp) }
    val ids: List<String> get() = regions.map { it.id }
    val cells: List<Pair<Int, Int>> by lazy { regions.flatMap { it.cells } }
    val area: Int get() = regions.sumOf { it.area }
}

/**
 * The land as a mask (docs/art/PERSPECTIVE-MAP.md §5, mobile repo; the owner, 13 October: «не обязательно, чтобы остров
 * был ровно квадратным»): the buildable cells follow the meadow painted in the owner's picture, one 10 m cell each, in
 * `land_mask.json` — written by the art pipeline, byte-identical in this server's resources and in the client, which
 * compares its copy's [id] with the one GET /club sends. The server is the authority. [regions]: the main island (the
 * club's plot, unlocked) and the side islands, each locked by the zone sport whose arena opens it (the club's zone of that
 * sport OPEN: [of]). Anchors: the stadium's and the first ticket office's top-left cells (a new club's two buildings),
 * the bridges' feet on the main island by island id (the cell where each bridge's crossing meets the buildable land:
 * the roads end there, Showcase's spurs).
 */
class LandMap(
    val id: String,
    val regions: List<LandRegion>,
    val stadium: Pair<Int, Int>,
    val ticket: Pair<Int, Int>,
    val bridges: Map<String, Pair<Int, Int>> = emptyMap(),
    json: String? = null,
) {
    /** The main island: the unlocked region the stadium stands on. */
    val main: LandRegion = regions.firstOrNull { it.lock == null && it.has(stadium.first, stadium.second) }
        ?: throw IllegalArgumentException("land mask: no unlocked region holds the stadium anchor $stadium")
    /** The plot every club owns: the main island's cells. */
    val home = LandSet(listOf(main))

    /** The text GET /catalog/land serves: the file itself when it came from one. */
    val json: String = json ?: encode()

    /** The land of a club whose arenas of [open] sports are open: the main island and the side islands those sports lock. */
    fun of(open: Set<ZoneSport>): LandSet =
        if (open.isEmpty()) home else LandSet(listOf(main) + regions.filter { it !== main && it.sport != null && it.sport in open })

    /** The regions not in [land]: the side islands still locked. */
    fun locked(land: LandSet): List<LandRegion> = regions.filter { r -> land.regions.none { it === r } }

    /** The side island [sport]'s zone opens (null when this mask has none). */
    fun island(sport: ZoneSport): LandRegion? = regions.firstOrNull { it !== main && it.sport == sport }

    /**
     * A [type] may stand at [fp] on [land]: an island arena (BuildingSpec.island) on its own side island only, and only
     * once [land] holds it (its zone open); anything else anywhere on [land].
     */
    fun allows(land: LandSet, type: BuildingType, fp: Footprint): Boolean {
        val sport = BuildingCatalog.spec(type).island ?: return land.fits(fp)
        val isle = island(sport) ?: return false
        return land.regions.any { it === isle } && isle.fits(fp)
    }

    /** The cells a [type] may stand on in [land]: its own island's for an island arena (none while it is locked), else the land's. */
    fun cellsFor(land: LandSet, type: BuildingType): List<Pair<Int, Int>> {
        val sport = BuildingCatalog.spec(type).island ?: return land.cells
        val isle = island(sport)?.takeIf { i -> land.regions.any { it === i } } ?: return emptyList()
        return isle.cells
    }

    /** The sha1 of the canonical text ([canonical]); equals [id] for a well-formed file. */
    val computedId: String get() = sha1(regions)

    init {
        require(regions.map { it.id }.toSet().size == regions.size) { "land mask: region ids repeat" }
        for (i in regions.indices) for (j in i + 1 until regions.size)
            require(!regions[i].overlapsBox(regions[j])) { "land mask: regions ${regions[i].id} and ${regions[j].id} overlap" }
        val pitch = footprint(BuildingType.PITCH, stadium.first, stadium.second, 0)
        val office = footprint(BuildingType.TICKET_OFFICE, ticket.first, ticket.second, 0)
        require(main.fits(pitch)) { "land mask: the stadium at $stadium is off the main island" }
        require(main.fits(office) && !office.overlaps(pitch)) { "land mask: the ticket office at $ticket is off the main island or on the stadium" }
    }

    private fun encode(): String = pretty.encodeToString(MaskFile(1, id, CELL, FRAME,
        AnchorsFile(stadium.toList(), ticket.toList(), bridges.mapValues { it.value.toList() }),
        regions.map { RegionFile(it.id, it.lock, it.x0, it.y0, it.w, it.h, it.rows) })) + "\n"

    @Serializable private data class MaskFile(val version: Int = 1, val id: String, val cell: Int = CELL, val frame: String = "", val anchors: AnchorsFile, val regions: List<RegionFile>)
    @Serializable private data class AnchorsFile(val stadium: List<Int>, val ticket: List<Int>, val bridges: Map<String, List<Int>> = emptyMap())
    @Serializable private data class RegionFile(val id: String, val lock: String? = null, val x0: Int, val y0: Int, val w: Int, val h: Int, val rows: List<String>)

    companion object {
        const val CELL = 10
        const val FRAME = "cell (x,y) = world x in [10x,10x+10), z in [10y,10y+10), y = 0"
        private val format = Json { ignoreUnknownKeys = true }
        private val pretty = Json { prettyPrint = true }

        /**
         * The text the id hashes, the same in the art pipeline (Python), the client (C#) and here: over the regions sorted
         * by id (ordinal), `id|lock|x0|y0|w|h|row0/row1/…` joined with "\n"; a missing lock is "-".
         */
        fun canonical(regions: List<LandRegion>) = regions.sortedBy { it.id }
            .joinToString("\n") { r -> "${r.id}|${r.lock ?: "-"}|${r.x0}|${r.y0}|${r.w}|${r.h}|${r.rows.joinToString("/")}" }

        fun sha1(regions: List<LandRegion>): String =
            MessageDigest.getInstance("SHA-1").digest(canonical(regions).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        /** A mask from `land_mask.json`; the file's own id is kept (it is what the client compares). */
        fun parse(text: String): LandMap {
            val f = format.decodeFromString<MaskFile>(text)
            require(f.version == 1) { "land mask: version ${f.version}" }
            require(f.cell == CELL) { "land mask: cells of ${f.cell} m" }
            val regions = f.regions.map { r ->
                require(r.rows.size == r.h) { "land mask: region ${r.id} has ${r.rows.size} rows, not ${r.h}" }
                LandRegion.of(r.id, r.lock, r.x0, r.y0, r.rows).also { require(it.w == r.w) { "land mask: region ${r.id} is ${it.w} wide, not ${r.w}" } }
            }
            fun cell(p: List<Int>, what: String) = p.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: throw IllegalArgumentException("land mask: bad $what anchor $p")
            return LandMap(f.id, regions, cell(f.anchors.stadium, "stadium"), cell(f.anchors.ticket, "ticket"),
                f.anchors.bridges.mapValues { (k, v) -> cell(v, "bridge $k") }, text)
        }

        /** A mask built in code (the fallback square, tests): its id is the sha1 of its regions. */
        fun of(regions: List<LandRegion>, stadium: Pair<Int, Int>, ticket: Pair<Int, Int>, bridges: Map<String, Pair<Int, Int>> = emptyMap()) =
            LandMap(sha1(regions), regions, stadium, ticket, bridges)
    }
}

/** The game's land: the mask in this server's resources, loaded once. */
object Lands {
    /** Written by the art pipeline (`persp_build`), byte-identical to the client's `unity/Assets/Art/City/land_mask.json`. */
    const val RESOURCE = "/land/land_mask.json"
    /**
     * Where a building waits when the land has no room for it (ClubService.settle): row [PARKED], every tenth column from
     * [PARKED] down, so no two footprints ever touch; the client lists such a building as off the land, to be moved by hand.
     */
    const val PARKED = -10_000
    /**
     * The first client version that builds on the land mask (reads `land.maskId`, draws the owner's picture): an older one
     * validates the old square and would show the towns the migration moved onto the new cells standing in its sea, its
     * edit-mode Save locked. `client.minVersion` (GET /config) must be at least this wherever this mask is served.
     */
    const val MIN_CLIENT = "0.2.0"

    /** The old plot (cells -9..38 on both axes, the archipelago v2) as one region: the land until the art pipeline installs its mask. */
    val SQUARE: LandMap by lazy { LandMap.of(listOf(LandRegion.of("main", null, -9, -9, List(48) { "#".repeat(48) })), 10 to 10, 4 to 22) }

    val MAP: LandMap by lazy { load(RESOURCE) ?: SQUARE }
    val ID: String get() = MAP.id

    fun load(resource: String): LandMap? = Lands::class.java.getResourceAsStream(resource)?.use { LandMap.parse(it.readBytes().toString(Charsets.UTF_8)) }

    /** The [i]-th parking cell; [row] 0 for a building the land has no room for, 1 for one parked for a moment mid-way. */
    fun parked(i: Int, row: Int = 0) = PARKED - 10 * i to PARKED - 10 * row
    fun isParked(y: Int) = y <= PARKED
}
