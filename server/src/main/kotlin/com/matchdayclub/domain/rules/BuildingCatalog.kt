package com.matchdayclub.domain.rules

import com.matchdayclub.domain.rules.BuildingType.*
import kotlin.math.ceil
import kotlin.math.min

enum class BuildingType { PITCH, BASKETBALL_COURT, TENNIS_COURT, ACADEMY, TRAINING_CENTER, MEDICAL_CENTER, TICKET_OFFICE, CLUB_SHOP, CAFE, PARKING, DECOR, FENCE, MUSEUM }

/**
 * One level of a building. [effect] meaning depends on type:
 * PITCH = athlete attribute cap, courts = section attribute cap, ACADEMY = min quality (max = effect + 15),
 * TRAINING_CENTER = slots, MEDICAL_CENTER = heals per cooldown; [effect2]: PITCH = seats, TRAINING_CENTER = attribute
 * delta per training, MEDICAL_CENTER = cooldown hours.
 * [requiredStadium]: the stadium level this level needs (the stadium is the club's town hall, see [Stadium]);
 * [requiredTrophies]: for the stadium itself — the arena standing a bigger stadium needs.
 */
data class LevelSpec(
    val cost: Long,
    val buildSeconds: Long,
    val requiredStadium: Int = 1,
    val requiredTrophies: Int = 0,
    val incomePerHour: Long = 0,
    val capHours: Int = 0,
    val effect: Int = 0,
    val effect2: Int = 0,
)

/**
 * [counts]/[caps]: how many of the building a club may have and the highest level it may reach, by stadium level
 * (index 0 = stadium 1). The stadium itself has neither (count 1, its own levels).
 */
data class BuildingSpec(val type: BuildingType, val width: Int, val height: Int, val enabled: Boolean, val levels: List<LevelSpec>,
                        val counts: List<Int> = List(Stadium.MAX_LEVEL) { 1 }, val caps: List<Int> = List(Stadium.MAX_LEVEL) { levels.size }) {
    val maxLevel get() = levels.size
    /** Most copies at the top stadium (Int.MAX_VALUE stays "unlimited" for nothing but the old API field). */
    val maxPerClub get() = counts.last()
    fun countAt(stadium: Int) = counts[stadium.coerceIn(1, Stadium.MAX_LEVEL) - 1]
    fun capAt(stadium: Int) = minOf(caps[stadium.coerceIn(1, Stadium.MAX_LEVEL) - 1], maxLevel)
    /** The first stadium level that allows a copy (0 = never). */
    val unlockStadium get() = counts.indexOfFirst { it > 0 } + 1
}

/**
 * The stadium (the PITCH building) is the club's town hall, like in Clash of Clans: its level caps how far every other
 * building goes and how many of each a club may have, and a bigger stadium needs a higher arena (trophies). While the
 * stadium itself is being upgraded the club keeps the previous level's limits.
 * Tuned with tools/claude/simtest/progression.py (an active player: stadium 5 in ~3 weeks, 8 in ~2 months, 10 in ~3–4).
 */
object Stadium {
    const val MAX_LEVEL = 10
    fun level(buildings: List<com.matchdayclub.domain.model.Building>): Int =
        buildings.firstOrNull { it.type == BuildingType.PITCH }?.let { if (it.state == com.matchdayclub.domain.model.BuildingState.UPGRADING) it.level - 1 else it.level }?.coerceAtLeast(1) ?: 1
    /** The club plot grows with the stadium (and never shrinks below what the club level already gave). */
    fun gridSize(stadium: Int): Int = min(12 + (stadium - 1) * 2, 30)
    fun decorCount(stadium: Int): Int = 8 + 4 * stadium
    fun fenceCount(stadium: Int): Int = 8 * stadium
    /** Types that build and upgrade instantly and never occupy a builder (and do not count as "real" buildings). */
    val COSMETIC = setOf(BuildingType.DECOR, BuildingType.FENCE)
}

object BuildingCatalog {
    private const val D = 86_400L
    private const val H = 3600L
    private const val M = 60L
    private fun L(cost: Int, seconds: Long, stadium: Int = 1, trophies: Int = 0, incomePerHour: Long = 0, capHours: Int = 0, effect: Int = 0, effect2: Int = 0) =
        LevelSpec(cost.toLong(), seconds, stadium, trophies, incomePerHour, capHours, effect, effect2)

    private val CAP10 = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
    private val CAP8 = listOf(1, 2, 3, 4, 4, 5, 6, 6, 7, 8)
    private fun countsFrom(vararg c: Int) = c.toList()

    private val levelTable: Map<BuildingType, List<LevelSpec>> = mapOf(
        PITCH to listOf(
            L(0, 0, effect = 60, effect2 = 500),
            L(2_300, 2 * H, effect = 64, effect2 = 1_200),
            L(7_700, 6 * H, trophies = 300, effect = 68, effect2 = 2_500),
            L(22_000, 12 * H, trophies = 700, effect = 72, effect2 = 4_000),
            L(52_000, 20 * H, trophies = 700, effect = 76, effect2 = 6_000),
            L(110_000, 30 * H, trophies = 1_300, effect = 80, effect2 = 8_500),
            L(210_000, 42 * H, trophies = 2_200, effect = 84, effect2 = 11_500),
            L(380_000, 2 * D, trophies = 2_200, effect = 88, effect2 = 15_000),
            L(640_000, 3 * D, trophies = 3_500, effect = 93, effect2 = 20_000),
            L(1_000_000, 4 * D, trophies = 5_500, effect = 99, effect2 = 26_000),
        ),
        TICKET_OFFICE to listOf(
            L(900, 5 * M, stadium = 1, incomePerHour = 120, capHours = 6),
            L(1_900, 10 * M, stadium = 2, incomePerHour = 150, capHours = 7),
            L(3_800, 20 * M, stadium = 3, incomePerHour = 200, capHours = 8),
            L(7_900, 45 * M, stadium = 4, incomePerHour = 260, capHours = 9),
            L(16_000, 90 * M, stadium = 5, incomePerHour = 330, capHours = 10),
            L(33_000, 3 * H, stadium = 6, incomePerHour = 430, capHours = 11),
            L(69_000, 7 * H, stadium = 7, incomePerHour = 560, capHours = 12),
            L(140_000, 14 * H, stadium = 8, incomePerHour = 720, capHours = 13),
            L(290_000, 29 * H, stadium = 9, incomePerHour = 930, capHours = 14),
            L(600_000, 60 * H, stadium = 10, incomePerHour = 1_200, capHours = 16),
        ),
        CLUB_SHOP to listOf(
            L(3_000, 30 * M, stadium = 2, incomePerHour = 200, capHours = 6),
            L(5_500, 50 * M, stadium = 2, incomePerHour = 250, capHours = 7),
            L(10_000, 90 * M, stadium = 3, incomePerHour = 310, capHours = 8),
            L(19_000, 150 * M, stadium = 4, incomePerHour = 390, capHours = 9),
            L(34_000, 270 * M, stadium = 5, incomePerHour = 490, capHours = 10),
            L(63_000, 8 * H, stadium = 6, incomePerHour = 620, capHours = 11),
            L(120_000, 13 * H, stadium = 7, incomePerHour = 770, capHours = 12),
            L(210_000, 22 * H, stadium = 8, incomePerHour = 970, capHours = 13),
            L(390_000, 38 * H, stadium = 9, incomePerHour = 1_200, capHours = 14),
            L(720_000, 66 * H, stadium = 10, incomePerHour = 1_500, capHours = 16),
        ),
        CAFE to listOf(
            L(2_100, 20 * M, stadium = 2, incomePerHour = 150, capHours = 6),
            L(4_000, 35 * M, stadium = 2, incomePerHour = 190, capHours = 7),
            L(7_500, 1 * H, stadium = 3, incomePerHour = 240, capHours = 8),
            L(14_000, 2 * H, stadium = 4, incomePerHour = 310, capHours = 9),
            L(27_000, 210 * M, stadium = 5, incomePerHour = 400, capHours = 10),
            L(51_000, 6 * H, stadium = 6, incomePerHour = 510, capHours = 11),
            L(97_000, 11 * H, stadium = 7, incomePerHour = 650, capHours = 12),
            L(180_000, 19 * H, stadium = 8, incomePerHour = 830, capHours = 13),
            L(350_000, 34 * H, stadium = 9, incomePerHour = 1_100, capHours = 14),
            L(660_000, 60 * H, stadium = 10, incomePerHour = 1_400, capHours = 16),
        ),
        PARKING to listOf(
            L(4_500, 45 * M, stadium = 3, incomePerHour = 250, capHours = 6),
            L(8_000, 1 * H, stadium = 3, incomePerHour = 310, capHours = 7),
            L(14_000, 2 * H, stadium = 3, incomePerHour = 390, capHours = 8),
            L(25_000, 210 * M, stadium = 4, incomePerHour = 480, capHours = 9),
            L(44_000, 330 * M, stadium = 5, incomePerHour = 600, capHours = 10),
            L(79_000, 9 * H, stadium = 6, incomePerHour = 740, capHours = 11),
            L(140_000, 16 * H, stadium = 7, incomePerHour = 920, capHours = 12),
            L(250_000, 26 * H, stadium = 8, incomePerHour = 1_100, capHours = 13),
            L(440_000, 43 * H, stadium = 9, incomePerHour = 1_400, capHours = 14),
            L(780_000, 3 * D, stadium = 10, incomePerHour = 1_800, capHours = 16),
        ),
        ACADEMY to listOf(
            L(1_500, 10 * M, stadium = 1, effect = 40),
            L(3_400, 25 * M, stadium = 2, effect = 44),
            L(7_700, 55 * M, stadium = 3, effect = 48),
            L(17_000, 2 * H, stadium = 4, effect = 52),
            L(39_000, 5 * H, stadium = 6, effect = 57),
            L(88_000, 11 * H, stadium = 7, effect = 62),
            L(200_000, 26 * H, stadium = 9, effect = 67),
            L(450_000, 60 * H, stadium = 10, effect = 72),
        ),
        TRAINING_CENTER to listOf(
            L(2_400, 20 * M, stadium = 1, effect = 1, effect2 = 1),
            L(5_100, 40 * M, stadium = 2, effect = 1, effect2 = 2),
            L(11_000, 90 * M, stadium = 3, effect = 2, effect2 = 2),
            L(23_000, 3 * H, stadium = 4, effect = 2, effect2 = 2),
            L(50_000, 6 * H, stadium = 6, effect = 3, effect2 = 3),
            L(110_000, 14 * H, stadium = 7, effect = 3, effect2 = 3),
            L(230_000, 29 * H, stadium = 9, effect = 4, effect2 = 3),
            L(480_000, 60 * H, stadium = 10, effect = 4, effect2 = 4),
        ),
        MEDICAL_CENTER to listOf(
            L(1_800, 15 * M, stadium = 2, effect = 1, effect2 = 5),
            L(3_900, 35 * M, stadium = 2, effect = 1, effect2 = 4),
            L(8_500, 1 * H, stadium = 3, effect = 2, effect2 = 4),
            L(19_000, 150 * M, stadium = 4, effect = 2, effect2 = 3),
            L(41_000, 330 * M, stadium = 6, effect = 2, effect2 = 3),
            L(88_000, 13 * H, stadium = 7, effect = 3, effect2 = 3),
            L(190_000, 27 * H, stadium = 9, effect = 3, effect2 = 2),
            L(420_000, 60 * H, stadium = 10, effect = 4, effect2 = 2),
        ),
        BASKETBALL_COURT to listOf(
            L(7_500, 2 * H, stadium = 3, effect = 60),
            L(14_000, 210 * M, stadium = 4, effect = 65),
            L(25_000, 330 * M, stadium = 5, effect = 70),
            L(47_000, 9 * H, stadium = 6, effect = 75),
            L(86_000, 14 * H, stadium = 7, effect = 80),
            L(160_000, 23 * H, stadium = 8, effect = 85),
            L(290_000, 37 * H, stadium = 9, effect = 90),
            L(540_000, 60 * H, stadium = 10, effect = 95),
        ),
        TENNIS_COURT to listOf(
            L(12_000, 3 * H, stadium = 4, effect = 60),
            L(21_000, 270 * M, stadium = 4, effect = 65),
            L(37_000, 7 * H, stadium = 5, effect = 70),
            L(64_000, 11 * H, stadium = 6, effect = 75),
            L(110_000, 18 * H, stadium = 7, effect = 80),
            L(200_000, 27 * H, stadium = 8, effect = 85),
            L(340_000, 42 * H, stadium = 9, effect = 90),
            L(600_000, 66 * H, stadium = 10, effect = 95),
        ),
        DECOR to listOf(L(200, 0)),
        // the trophy museum: effect = exhibits on its shelves, effect2 = atmosphere % to earning buildings around it
        MUSEUM to listOf(
            L(9_000, 4 * H, stadium = 3, effect = 6, effect2 = 2),
            L(25_000, 10 * H, stadium = 5, effect = 12, effect2 = 4),
            L(70_000, 20 * H, stadium = 6, effect = 20, effect2 = 6),
            L(180_000, 36 * H, stadium = 8, effect = 30, effect2 = 8),
            L(450_000, 60 * H, stadium = 10, effect = 45, effect2 = 10),
        ),
        // fences: drawn along the plot by dragging, instant to build and to upgrade (no builder) — a coin sink that
        // changes the look: wooden picket → hedge → brick → stone → steel → glass with an LED rail
        FENCE to listOf(L(30, 0), L(150, 0, stadium = 2), L(600, 0, stadium = 3), L(2_000, 0, stadium = 5), L(6_000, 0, stadium = 7), L(15_000, 0, stadium = 9)),
    )

    val specs: Map<BuildingType, BuildingSpec> = listOf(
        BuildingSpec(PITCH, 8, 6, true, levelTable.getValue(PITCH)),
        // courts open a sport section (starter squad on completion); level = attribute cap of that section, like the pitch
        BuildingSpec(BASKETBALL_COURT, 3, 2, true, levelTable.getValue(BASKETBALL_COURT), countsFrom(0, 0, 1, 1, 1, 1, 1, 1, 1, 1), listOf(0, 0, 1, 2, 3, 4, 5, 6, 7, 8)),
        BuildingSpec(TENNIS_COURT, 3, 2, true, levelTable.getValue(TENNIS_COURT), countsFrom(0, 0, 0, 1, 1, 1, 1, 1, 1, 1), listOf(0, 0, 0, 2, 3, 4, 5, 6, 7, 8)),
        BuildingSpec(ACADEMY, 2, 2, true, levelTable.getValue(ACADEMY), List(10) { 1 }, CAP8),
        BuildingSpec(TRAINING_CENTER, 2, 2, true, levelTable.getValue(TRAINING_CENTER), List(10) { 1 }, CAP8),
        BuildingSpec(MEDICAL_CENTER, 2, 2, true, levelTable.getValue(MEDICAL_CENTER), countsFrom(0, 1, 1, 1, 1, 1, 1, 1, 1, 1), CAP8),
        BuildingSpec(TICKET_OFFICE, 2, 1, true, levelTable.getValue(TICKET_OFFICE), countsFrom(1, 1, 1, 2, 2, 2, 2, 3, 3, 3), CAP10),
        BuildingSpec(CLUB_SHOP, 2, 2, true, levelTable.getValue(CLUB_SHOP), countsFrom(0, 1, 1, 1, 1, 2, 2, 2, 3, 3), CAP10),
        BuildingSpec(CAFE, 2, 2, true, levelTable.getValue(CAFE), countsFrom(0, 1, 1, 1, 2, 2, 2, 3, 3, 3), CAP10),
        BuildingSpec(PARKING, 3, 2, true, levelTable.getValue(PARKING), countsFrom(0, 0, 1, 1, 1, 1, 2, 2, 2, 2), CAP10),
        BuildingSpec(DECOR, 1, 1, true, levelTable.getValue(DECOR), List(10) { Stadium.decorCount(it + 1) }, List(10) { 1 }),
        BuildingSpec(MUSEUM, 2, 2, true, levelTable.getValue(MUSEUM), countsFrom(0, 0, 1, 1, 1, 1, 1, 1, 1, 1), listOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5)),
        BuildingSpec(FENCE, 1, 1, true, levelTable.getValue(FENCE), List(10) { Stadium.fenceCount(it + 1) }, listOf(1, 2, 3, 3, 4, 4, 5, 5, 6, 6)),
    ).associateBy { it.type }

    fun spec(type: BuildingType): BuildingSpec = specs.getValue(type)
    fun level(type: BuildingType, level: Int): LevelSpec = spec(type).levels[level.coerceIn(1, spec(type).maxLevel) - 1]
    fun hasLevel(type: BuildingType, level: Int) = level in 1..spec(type).maxLevel

    /** Coins earned since the last collection, capped by the building's storage. */
    fun income(spec: LevelSpec, secondsSinceCollect: Long, clubLevel: Int, atmospherePct: Int = 0): Long {
        if (spec.incomePerHour == 0L) return 0
        val hours = min(secondsSinceCollect / 3600.0, spec.capHours.toDouble())
        val multiplier = (1.0 + (clubLevel - 1) * 0.05) * (1.0 + atmospherePct / 100.0)
        return (spec.incomePerHour * hours * multiplier).toLong()
    }

    /** 1 gold per started 10 minutes, minimum 1. */
    fun speedUpGold(remainingSeconds: Long): Int = if (remainingSeconds <= 0) 0 else maxOf(1, ceil(remainingSeconds / 600.0).toInt())
}

object ClubLevels {
    private val thresholds = listOf(0, 100, 300, 700, 1_500, 3_000, 5_500, 9_000, 14_000, 20_000)
    const val MAX_LEVEL = 10
    fun levelFor(reputation: Int): Int = thresholds.indexOfLast { reputation >= it } + 1
    fun gridSize(level: Int): Int = min(12 + (level - 1) * 2, 30)
    fun reputationForNext(level: Int): Int? = thresholds.getOrNull(level)
}

/**
 * The club's land is a 30×30-cell square; the grid the club may build on is the centred [gridSize]×[gridSize] part of
 * it, so the stadium placed in the middle stays in the middle as the grid grows outwards on every side.
 */
object Grid {
    const val RESERVE = 30
    fun lo(gridSize: Int) = (RESERVE - gridSize) / 2
    fun hi(gridSize: Int) = lo(gridSize) + gridSize
    const val CENTRE = RESERVE / 2
}

data class Footprint(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun fits(gridSize: Int) = x >= Grid.lo(gridSize) && y >= Grid.lo(gridSize) && x + w <= Grid.hi(gridSize) && y + h <= Grid.hi(gridSize)
    fun overlaps(o: Footprint) = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
    fun contains(o: Footprint) = o.x >= x && o.y >= y && o.x + o.w <= x + w && o.y + o.h <= y + h
}

/**
 * Each other sport has a plot of its own at an edge of the club's land (cells of the 30x30 square, outside the first
 * grids): a derelict court stands in its middle until the stadium allows the sport, then the club restores it there.
 * A court may be placed or moved inside its plot even when the plot lies outside the current grid; anywhere else the
 * grid rules apply as usual.
 */
data class ReservedPlot(val type: BuildingType, val plot: Footprint, val courtX: Int, val courtY: Int)
object ReservedPlots {
    val ALL = listOf(
        ReservedPlot(BuildingType.BASKETBALL_COURT, Footprint(23, 12, 7, 6), 25, 14),   // the east edge of the land
        ReservedPlot(BuildingType.TENNIS_COURT, Footprint(0, 12, 7, 6), 2, 14),          // the west edge
    )
    fun of(type: BuildingType): ReservedPlot? = ALL.firstOrNull { it.type == type }
    /** Inside the grid, or a court inside its own plot. */
    fun allowed(type: BuildingType, fp: Footprint, gridSize: Int) = fp.fits(gridSize) || of(type)?.plot?.contains(fp) == true
}

fun footprint(type: BuildingType, x: Int, y: Int, rotation: Int): Footprint {
    val s = BuildingCatalog.spec(type)
    return if (rotation % 180 == 0) Footprint(x, y, s.width, s.height) else Footprint(x, y, s.height, s.width)
}
