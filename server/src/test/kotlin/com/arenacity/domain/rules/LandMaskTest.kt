package com.arenacity.domain.rules

import java.io.File
import kotlin.test.*

/**
 * The land mask the server ships (resources/land/land_mask.json, written by the art pipeline; the old square until it
 * is): the contract every club and both showcase towns rely on (docs/art/PERSPECTIVE-MAP.md §5.6, mobile repo).
 */
class LandMaskTest {
    private val lands = Lands.MAP

    @Test
    fun `the shipped mask is sound`() {
        assertEquals(lands.computedId, lands.id, "the id is the sha1 of the canonical regions")
        val main = lands.main
        assertNull(main.lock)
        assertTrue(main.area >= 2304, "the main island holds ${main.area} cells, the old plot had 2304")
        // the main island is one piece (4-connected)
        val cells = main.cells.toSet()
        val seen = HashSet<Pair<Int, Int>>(); val queue = ArrayDeque(listOf(cells.first())); seen += cells.first()
        while (queue.isNotEmpty()) { val (x, y) = queue.removeFirst(); for (n in listOf(x + 1 to y, x - 1 to y, x to y + 1, x to y - 1)) if (n in cells && seen.add(n)) queue.addLast(n) }
        assertEquals(cells.size, seen.size, "the main island is one piece")
        // a new club's stadium and ticket office stand on it apart; the side islands are the peoples' this server knows
        val pitch = footprint(BuildingType.PITCH, lands.stadium.first, lands.stadium.second, 0)
        val office = footprint(BuildingType.TICKET_OFFICE, lands.ticket.first, lands.ticket.second, 0)
        assertTrue(main.fits(pitch) && main.fits(office) && !pitch.overlaps(office))
        for (r in lands.regions - main) assertNotNull(r.people, "the side island ${r.id} is locked by an unknown people ${r.lock}")
        for (i in lands.regions.indices) for (j in i + 1 until lands.regions.size) assertFalse(lands.regions[i].overlapsBox(lands.regions[j]))
        // the parking cells are off every island
        for (i in 0 until 50) for (row in 0..1) { val (x, y) = Lands.parked(i, row); assertTrue(lands.regions.none { it.has(x, y) }) }
        // the bridges' feet (where each crossing meets the buildable land, the roads' end) stand on the main island, at its edge
        for ((id, b) in lands.bridges) {
            assertTrue(main.has(b.first, b.second), "the $id bridge's foot is off the main island")
            assertTrue((-2..2).any { dx -> (-2..2).any { dy -> !main.has(b.first + dx, b.second + dy) } }, "the $id bridge's foot is far from the land's edge")
        }
        // both showcase towns fit it (ShowcaseTest checks them in full)
        for (stadium in listOf(5, 10)) {
            val ground = LandSet(listOf(main) + Showcase.islands(stadium, lands))   // the main island and the open peoples' islands
            assertTrue(Showcase.city(stadium, lands).slots.all { lands.allows(ground, it.type, footprint(it.type, it.x, it.y, 0)) })
        }
    }

    @Test
    fun `clients older than the mask are asked to update`() {
        // an older client validates the old square: GET /config's minVersion must turn it away wherever this mask is served
        // (application.conf, and the deploy's defaults when the repo is at hand)
        if (lands === Lands.SQUARE) return
        val conf = com.typesafe.config.ConfigFactory.parseResources("application.conf").resolve()
        val served = mutableMapOf("application.conf" to conf.getString("client.minVersion"))
        for (f in listOf("../docker-compose.yml", "../.env.example", "../deploy/vps/env.prod.example").map(::File).filter { it.exists() })
            Regex("""CLIENT_MIN_VERSION(?::\s*\$\{CLIENT_MIN_VERSION:-|=)([0-9.]+)""").find(f.readText())?.let { served[f.name] = it.groupValues[1] }
        for ((where, v) in served) assertTrue(version(v) >= version(Lands.MIN_CLIENT), "$where: client.minVersion $v, the land mask needs ${Lands.MIN_CLIENT}")
    }

    private fun version(v: String) = v.split('.').map { p -> p.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }.let { p -> (0 until 3).fold(0L) { a, i -> a * 10_000 + (p.getOrNull(i) ?: 0) } }

    @Test
    fun `the client bundles the same file`() {
        // the art pipeline writes both copies byte for byte; checked when the mobile repo stands next to this one
        val server = File("src/main/resources/land/land_mask.json")
        val client = File("../../Arena-Game-Mobile/unity/Assets/Art/City/land_mask.json")
        if (!server.exists() || !client.exists()) return
        assertContentEquals(server.readBytes(), client.readBytes(), "land_mask.json differs between the server and the client")
    }
}
