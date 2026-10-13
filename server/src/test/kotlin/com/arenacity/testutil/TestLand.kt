package com.arenacity.testutil

import com.arenacity.domain.rules.LandMap
import com.arenacity.domain.rules.LandRegion

/** Land masks for tests that need a known shape, whatever mask the server ships (rules.Lands). */
object TestLand {
    /**
     * The old square plot (cells -9..38 on both axes) with its top-right corner cut off (x 30..38, y -9..0 is sea), and a
     * side island locked by tennis to its right (x 50..59, y 0..9) with its bridge slot on the main island's shore.
     */
    val NOTCHED: LandMap by lazy {
        val main = LandRegion.of("main", null, -9, -9, (-9..38).map { y -> (-9..38).joinToString("") { x -> if (x >= 30 && y <= 0) "." else "#" } })
        val tennis = LandRegion.of("tennis", "TENNIS", 50, 0, List(10) { "#".repeat(10) })
        LandMap.of(listOf(main, tennis), 11 to 12, 9 to 9, mapOf("tennis" to (38 to 5)))
    }

    /**
     * An organic mask cut from the owner's picture (art/paint/persp/master.png in the mobile repo) the way the art pipeline
     * does (PERSPECTIVE-MAP.md 4.3: the meadow's cells at 85 % share, orthographic back-projection, scaled to ~2400 cells),
     * with four locked side islands: a stand-in of the real mask's shape and format, frozen in test resources.
     */
    val ORGANIC: LandMap by lazy { LandMap.parse(TestLand::class.java.getResourceAsStream("/land/organic_mask.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }) }
}
