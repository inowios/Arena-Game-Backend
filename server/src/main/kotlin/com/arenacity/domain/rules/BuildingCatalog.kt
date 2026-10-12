package com.arenacity.domain.rules

import com.arenacity.domain.rules.BuildingType.*
import kotlin.math.ceil
import kotlin.math.min

enum class BuildingType { PITCH, BASKETBALL_COURT, TENNIS_COURT, ACADEMY, TRAINING_CENTER, MEDICAL_CENTER, TICKET_OFFICE, CLUB_SHOP, CAFE, PARKING, DECOR, FENCE, MUSEUM,
    // the town the player builds on bought land (the eras map, 13 October): homes and workplaces pay tax, parks please, roads join
    HOUSE, APARTMENT, OFFICE, PARK, ROAD }

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
    fun level(buildings: List<com.arenacity.domain.model.Building>): Int =
        buildings.firstOrNull { it.type == BuildingType.PITCH }?.let { if (it.state == com.arenacity.domain.model.BuildingState.UPGRADING) it.level - 1 else it.level }?.coerceAtLeast(1) ?: 1
    /** The plot is the same 24-cell square at every level (the eras map, 12 October): buildings grow, the land does not. */
    fun gridSize(stadium: Int): Int = Grid.PLOT
    fun decorCount(stadium: Int): Int = 8 + 4 * stadium
    fun fenceCount(stadium: Int): Int = 8 * stadium
    /** Types that build and upgrade instantly and never occupy a builder (and do not count as "real" buildings). */
    val COSMETIC = setOf(BuildingType.DECOR, BuildingType.FENCE, BuildingType.ROAD)
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
        // the town on bought land: tax per hour; the look follows the era (levels 4-6 the town, 7-9 the city of the future, 10 the apex)
        HOUSE to listOf(
            L(2_500, 20 * M, stadium = 4, incomePerHour = 60, capHours = 8),
            L(4_500, 40 * M, stadium = 4, incomePerHour = 80, capHours = 8),
            L(8_000, 90 * M, stadium = 4, incomePerHour = 110, capHours = 9),
            L(14_000, 3 * H, stadium = 4, incomePerHour = 150, capHours = 9),
            L(24_000, 5 * H, stadium = 5, incomePerHour = 200, capHours = 10),
            L(40_000, 8 * H, stadium = 6, incomePerHour = 260, capHours = 10),
            L(70_000, 12 * H, stadium = 7, incomePerHour = 340, capHours = 11),
            L(120_000, 18 * H, stadium = 8, incomePerHour = 440, capHours = 12),
            L(200_000, 26 * H, stadium = 9, incomePerHour = 570, capHours = 13),
            L(330_000, 36 * H, stadium = 10, incomePerHour = 740, capHours = 14),
        ),
        APARTMENT to listOf(
            L(9_000, 2 * H, stadium = 5, incomePerHour = 180, capHours = 8),
            L(15_000, 3 * H, stadium = 5, incomePerHour = 230, capHours = 8),
            L(25_000, 5 * H, stadium = 5, incomePerHour = 300, capHours = 9),
            L(42_000, 8 * H, stadium = 5, incomePerHour = 390, capHours = 9),
            L(70_000, 12 * H, stadium = 5, incomePerHour = 500, capHours = 10),
            L(115_000, 16 * H, stadium = 6, incomePerHour = 640, capHours = 10),
            L(190_000, 22 * H, stadium = 7, incomePerHour = 820, capHours = 11),
            L(300_000, 30 * H, stadium = 8, incomePerHour = 1_050, capHours = 12),
            L(470_000, 40 * H, stadium = 9, incomePerHour = 1_300, capHours = 13),
            L(720_000, 54 * H, stadium = 10, incomePerHour = 1_650, capHours = 14),
        ),
        OFFICE to listOf(
            L(16_000, 3 * H, stadium = 6, incomePerHour = 260, capHours = 10),
            L(27_000, 5 * H, stadium = 6, incomePerHour = 330, capHours = 10),
            L(45_000, 8 * H, stadium = 6, incomePerHour = 430, capHours = 11),
            L(75_000, 12 * H, stadium = 6, incomePerHour = 550, capHours = 11),
            L(120_000, 16 * H, stadium = 6, incomePerHour = 700, capHours = 12),
            L(190_000, 22 * H, stadium = 6, incomePerHour = 890, capHours = 12),
            L(300_000, 30 * H, stadium = 7, incomePerHour = 1_130, capHours = 13),
            L(460_000, 40 * H, stadium = 8, incomePerHour = 1_430, capHours = 13),
            L(700_000, 52 * H, stadium = 9, incomePerHour = 1_800, capHours = 14),
            L(1_000_000, 66 * H, stadium = 10, incomePerHour = 2_250, capHours = 14),
        ),
        // parks: effect = atmosphere % to earning buildings around (like the museum)
        PARK to listOf(
            L(5_000, 1 * H, stadium = 4, effect2 = 3),
            L(12_000, 3 * H, stadium = 5, effect2 = 4),
            L(30_000, 6 * H, stadium = 6, effect2 = 5),
            L(70_000, 10 * H, stadium = 7, effect2 = 6),
            L(150_000, 16 * H, stadium = 8, effect2 = 7),
            L(300_000, 24 * H, stadium = 9, effect2 = 8),
            L(600_000, 36 * H, stadium = 10, effect2 = 10),
            L(900_000, 48 * H, stadium = 10, effect2 = 12),
        ),
        // roads: drawn by dragging like fences, instant; a coin sink that joins the town up
        ROAD to listOf(L(40, 0, stadium = 4), L(200, 0, stadium = 7), L(800, 0, stadium = 10)),
    )

    // Footprints (cells of 10 m) are fixed for all ten levels and generous — the owner, 12 October: a building never
    // changes its plot, growth is in height and richness; the plots differ by type and are big since the square is big.
    val specs: Map<BuildingType, BuildingSpec> = listOf(
        BuildingSpec(PITCH, 8, 6, true, levelTable.getValue(PITCH)),
        // courts open a sport section (starter squad on completion); level = attribute cap of that section, like the pitch
        BuildingSpec(BASKETBALL_COURT, 3, 2, true, levelTable.getValue(BASKETBALL_COURT), countsFrom(0, 0, 1, 1, 1, 1, 1, 1, 1, 1), listOf(0, 0, 1, 2, 3, 4, 5, 6, 7, 8)),
        BuildingSpec(TENNIS_COURT, 3, 2, true, levelTable.getValue(TENNIS_COURT), countsFrom(0, 0, 0, 1, 1, 1, 1, 1, 1, 1), listOf(0, 0, 0, 2, 3, 4, 5, 6, 7, 8)),
        BuildingSpec(ACADEMY, 5, 4, true, levelTable.getValue(ACADEMY), List(10) { 1 }, CAP8),
        BuildingSpec(TRAINING_CENTER, 6, 4, true, levelTable.getValue(TRAINING_CENTER), List(10) { 1 }, CAP8),
        BuildingSpec(MEDICAL_CENTER, 4, 4, true, levelTable.getValue(MEDICAL_CENTER), countsFrom(0, 1, 1, 1, 1, 1, 1, 1, 1, 1), CAP8),
        BuildingSpec(TICKET_OFFICE, 5, 2, true, levelTable.getValue(TICKET_OFFICE), countsFrom(1, 1, 1, 2, 2, 2, 2, 3, 3, 3), CAP10),
        BuildingSpec(CLUB_SHOP, 4, 3, true, levelTable.getValue(CLUB_SHOP), countsFrom(0, 1, 1, 1, 1, 2, 2, 2, 3, 3), CAP10),
        BuildingSpec(CAFE, 3, 3, true, levelTable.getValue(CAFE), countsFrom(0, 1, 1, 1, 2, 2, 2, 3, 3, 3), CAP10),
        BuildingSpec(PARKING, 5, 3, true, levelTable.getValue(PARKING), countsFrom(0, 0, 1, 1, 1, 1, 2, 2, 2, 2), CAP10),
        BuildingSpec(DECOR, 1, 1, true, levelTable.getValue(DECOR), List(10) { Stadium.decorCount(it + 1) }, List(10) { 1 }),
        BuildingSpec(MUSEUM, 4, 5, true, levelTable.getValue(MUSEUM), countsFrom(0, 0, 1, 1, 1, 1, 1, 1, 1, 1), listOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5)),
        BuildingSpec(FENCE, 1, 1, true, levelTable.getValue(FENCE), List(10) { Stadium.fenceCount(it + 1) }, listOf(1, 2, 3, 3, 4, 4, 5, 5, 6, 6)),
        BuildingSpec(HOUSE, 3, 3, true, levelTable.getValue(HOUSE), countsFrom(0, 0, 0, 4, 8, 12, 16, 20, 24, 30), CAP10),
        BuildingSpec(APARTMENT, 4, 3, true, levelTable.getValue(APARTMENT), countsFrom(0, 0, 0, 0, 2, 4, 6, 8, 10, 12), CAP10),
        BuildingSpec(OFFICE, 4, 4, true, levelTable.getValue(OFFICE), countsFrom(0, 0, 0, 0, 0, 2, 3, 4, 6, 8), CAP10),
        BuildingSpec(PARK, 4, 4, true, levelTable.getValue(PARK), countsFrom(0, 0, 0, 2, 3, 4, 5, 6, 8, 10), listOf(0, 0, 0, 1, 2, 3, 4, 5, 6, 8)),
        BuildingSpec(ROAD, 1, 1, true, levelTable.getValue(ROAD), countsFrom(0, 0, 0, 60, 120, 200, 300, 400, 500, 600), listOf(0, 0, 0, 1, 1, 1, 2, 2, 2, 3)),
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
    fun gridSize(level: Int): Int = Grid.PLOT   // the plot never grows, see Stadium.gridSize
    fun reputationForNext(level: Int): Int? = thresholds.getOrNull(level)
}

/**
 * The club's land is a 30×30-cell square; the grid the club may build on is the centred [gridSize]×[gridSize] part of
 * it, so the stadium placed in the middle stays in the middle as the grid grows outwards on every side.
 */
object Grid {
    /** The old club square (30 cells): the plot is its centred [PLOT] cells (3..26). The land round it is [Land]'s field. */
    const val RESERVE = 30
    const val PLOT = 24
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
 * Each other sport owns a quarter of the club's land (the 30x30 square), from its corner to the middle of the two
 * roads; a derelict court stands in the corner until the stadium allows the sport, then the club restores it there.
 * A court may be placed or moved inside its plot even when the plot lies outside the current grid; anywhere else the
 * grid rules apply as usual.
 */
data class ReservedPlot(val type: BuildingType, val plot: Footprint, val courtX: Int, val courtY: Int)
object ReservedPlots {
    /** Empty since the eras map (12 October): every court stands on the plot like any other building. */
    val ALL = emptyList<ReservedPlot>()
    fun of(type: BuildingType): ReservedPlot? = ALL.firstOrNull { it.type == type }
    /** Inside the grid, or a court inside its own plot. */
    fun allowed(type: BuildingType, fp: Footprint, gridSize: Int) = fp.fits(gridSize) || of(type)?.plot?.contains(fp) == true
}

fun footprint(type: BuildingType, x: Int, y: Int, rotation: Int): Footprint {
    val s = BuildingCatalog.spec(type)
    return if (rotation % 180 == 0) Footprint(x, y, s.width, s.height) else Footprint(x, y, s.height, s.width)
}

/**
 * The land round the club (the eras map, 13 October: the player buys the land and builds the town on it). The field is
 * [Grid.RESERVE] cells = [PARCELS]×[PARCELS] parcels of [PARCEL] cells; the club starts with the plot ([HOME]) and buys
 * parcels one by one, each adjacent to one it owns; a building fits when every cell of its footprint lies in an owned parcel.
 */
object Land {
    const val PARCEL = 12
    const val PARCELS = 6
    /** The field's first cell: the plot (cells 3..26) is parcels 2..3, so the field runs from cell -21 to 50. */
    const val ORIGIN = -21
    const val CELLS = PARCEL * PARCELS
    val HOME: Set<Pair<Int, Int>> = setOf(2 to 2, 3 to 2, 2 to 3, 3 to 3)
    const val BASE_PRICE = 4_000L
    fun parcelOf(x: Int, y: Int) = ((x - ORIGIN) / PARCEL) to ((y - ORIGIN) / PARCEL)
    fun inField(col: Int, row: Int) = col in 0 until PARCELS && row in 0 until PARCELS
    /** The first cell of parcel [col] (or row). */
    fun cell(col: Int) = ORIGIN + col * PARCEL
    /** Every cell of [fp] on owned land. */
    fun fits(fp: Footprint, owned: Set<Pair<Int, Int>>): Boolean {
        if (fp.x < ORIGIN || fp.y < ORIGIN || fp.x + fp.w > ORIGIN + CELLS || fp.y + fp.h > ORIGIN + CELLS) return false
        val (c0, r0) = parcelOf(fp.x, fp.y); val (c1, r1) = parcelOf(fp.x + fp.w - 1, fp.y + fp.h - 1)
        for (cx in c0..c1) for (cy in r0..r1) if ((cx to cy) !in owned) return false
        return true
    }
    fun adjacent(col: Int, row: Int, owned: Set<Pair<Int, Int>>) =
        (col - 1 to row) in owned || (col + 1 to row) in owned || (col to row - 1) in owned || (col to row + 1) in owned
    fun canBuy(col: Int, row: Int, owned: Set<Pair<Int, Int>>) = inField(col, row) && (col to row) !in owned && adjacent(col, row, owned)
    /** The next parcel's price grows with every one bought: 4 000, 6 200, 9 600, 14 900 … (rounded to 100). */
    fun price(bought: Int): Long = (BASE_PRICE * Math.pow(1.55, bought.toDouble())).toLong() / 100 * 100
}
