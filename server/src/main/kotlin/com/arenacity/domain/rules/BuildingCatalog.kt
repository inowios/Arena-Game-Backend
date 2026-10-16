package com.arenacity.domain.rules

import com.arenacity.domain.rules.BuildingType.*
import kotlin.math.ceil
import kotlin.math.min

/**
 * The sport town (the owner, 14 October 2026; art/sprites/town_l1 in the mobile repo): the club's buildings and the town's.
 * Gone with it (V36 moved every club onto this set): CLUB_SHOP, CAFE and PARKING (one SHOPPING_CENTER), MUSEUM (the
 * HALL_OF_FAME), PARK (the BACKYARD_COURT), the courts (the island arenas), APARTMENT and OFFICE. The island arenas
 * (BASKETBALL_, VOLLEYBALL_, TENNIS_ and PADEL_ARENA) went on 15 October 2026 (the owner: the side islands are the
 * peoples' homes, the game is football only; V44 refunded them). The recovery centre and the coaching HQ went on 16 October
 * (the owner's building system: recovery is part of the medical centre, the coaching HQ of the tactics room; V46 refunded them).
 */
enum class BuildingType { PITCH, ACADEMY, TRAINING_CENTER, MEDICAL_CENTER, TICKET_OFFICE, DECOR, FENCE,
    // the town: homes pay tax, roads join
    HOUSE, ROAD,
    // earning and pleasing: the shops, the honours, the yard courts, the fans, the press, the sponsors, the guests
    SHOPPING_CENTER, HALL_OF_FAME, BACKYARD_COURT, FAN_ZONE, MEDIA_CENTER, SPONSOR_OFFICE, HOTEL,
    // no mechanic yet: buildable and upgradable, their panel says what they will do and «soon» (BuildingSpec.soon)
    ATHLETES_RESIDENCE, YOUTH_SPORTS_SCHOOL, SPORTS_SCIENCE_LAB, SCHOOL, TRANSPORT_HUB, SCOUTING_OFFICE, ANALYTICS_CENTER }

/**
 * One level of a building. [effect] meaning depends on type:
 * PITCH = athlete attribute cap, ACADEMY = min quality
 * (max = effect + 15), TRAINING_CENTER = slots, MEDICAL_CENTER = heals per cooldown, HALL_OF_FAME = exhibits; [effect2]:
 * PITCH = seats, TRAINING_CENTER = attribute delta per training, MEDICAL_CENTER = cooldown hours, HALL_OF_FAME and
 * BACKYARD_COURT = atmosphere % to the earning buildings round it.
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
 * [soon]: no mechanic yet — buildable and upgradable, the client says what it will do and «soon».
 */
data class BuildingSpec(val type: BuildingType, val width: Int, val height: Int, val enabled: Boolean, val levels: List<LevelSpec>,
                        val counts: List<Int> = List(Stadium.MAX_LEVEL) { 1 }, val caps: List<Int> = List(Stadium.MAX_LEVEL) { levels.size },
                        val soon: Boolean = false) {
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
    /** The old plot's size at every level (the eras map, 12 October: buildings grow, the land does not); see [Grid]. */
    fun gridSize(stadium: Int): Int = Grid.PLOT
    fun decorCount(stadium: Int): Int = 4 + 2 * stadium
    fun fenceCount(stadium: Int): Int = 2 * stadium
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
    /** One copy from stadium [unlock] on. */
    private fun oneFrom(unlock: Int) = List(Stadium.MAX_LEVEL) { if (it + 1 >= unlock) 1 else 0 }

    /**
     * The ten levels of a building opening at stadium [unlock], spread evenly over the stadiums left: level 1 at [unlock],
     * all ten at the top (stadium 2: 1, 2, … 8, 10; stadium 6: 1, 3, 5, 7, 10).
     */
    private fun capsFrom(unlock: Int): List<Int> = (1..Stadium.MAX_LEVEL).map { s ->
        when { s < unlock -> 0; s == Stadium.MAX_LEVEL -> 10; else -> 1 + (s - unlock) * 9 / (Stadium.MAX_LEVEL - unlock) }
    }

    /** The stadium each level of [caps] needs: the first one whose cap allows it. */
    private fun stadiumsOf(caps: List<Int>, levels: Int = 10) = (1..levels).map { i -> (1..Stadium.MAX_LEVEL).first { caps[it - 1] >= i } }

    /**
     * The curve of a building with no mechanic yet, by the stadium it opens at: level 1's and level 10's cost and
     * build time (the other levels between them, evenly in proportion), alongside the existing buildings of that stage.
     */
    private val SOON_CURVES = mapOf(
        2 to listOf(2_500L, 450_000L, 20 * M, 48 * H), 3 to listOf(6_000L, 520_000L, 1 * H, 52 * H), 4 to listOf(10_000L, 600_000L, 2 * H, 56 * H),
        5 to listOf(15_000L, 680_000L, 3 * H, 60 * H), 6 to listOf(22_000L, 760_000L, 4 * H, 62 * H), 7 to listOf(35_000L, 840_000L, 6 * H, 64 * H),
        8 to listOf(60_000L, 920_000L, 8 * H, 66 * H),
    )

    /** Ten levels on [SOON_CURVES] of stadium [unlock]: costs to two significant digits, times to 5 minutes (to the hour from 2 h). */
    private fun soonLevels(unlock: Int): List<LevelSpec> {
        val (c1, c10, t1, t10) = SOON_CURVES.getValue(unlock)
        val stadiums = stadiumsOf(capsFrom(unlock))
        return (0 until 10).map { i ->
            val k = i / 9.0
            val cost = c1 * Math.pow(c10.toDouble() / c1, k)
            val digits = Math.pow(10.0, maxOf(0.0, Math.floor(Math.log10(cost)) - 1))
            val secs = t1 * Math.pow(t10.toDouble() / t1, k)
            val step = if (secs < 2 * H) 5 * M else H
            LevelSpec((Math.round(cost / digits) * digits).toLong(), Math.round(secs / step) * step, stadiums[i])
        }
    }

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
        // the club shop, the café and the car park in one (the sport town): the shop's costs and times, the best of the
        // three incomes (the car park's) at every level
        SHOPPING_CENTER to listOf(
            L(3_000, 30 * M, stadium = 2, incomePerHour = 250, capHours = 6),
            L(5_500, 50 * M, stadium = 2, incomePerHour = 310, capHours = 7),
            L(10_000, 90 * M, stadium = 3, incomePerHour = 390, capHours = 8),
            L(19_000, 150 * M, stadium = 4, incomePerHour = 480, capHours = 9),
            L(34_000, 270 * M, stadium = 5, incomePerHour = 600, capHours = 10),
            L(63_000, 8 * H, stadium = 6, incomePerHour = 740, capHours = 11),
            L(120_000, 13 * H, stadium = 7, incomePerHour = 920, capHours = 12),
            L(210_000, 22 * H, stadium = 8, incomePerHour = 1_100, capHours = 13),
            L(390_000, 38 * H, stadium = 9, incomePerHour = 1_400, capHours = 14),
            L(720_000, 66 * H, stadium = 10, incomePerHour = 1_800, capHours = 16),
        ),
        // the fans' square by the stadium: the old café's curve for now
        FAN_ZONE to listOf(
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
        // the press and the sponsors (stadium 6) and the hotel (stadium 8) earn for now: one copy each where the old town
        // had up to twelve apartments and eight offices, so each earns twice the old office's curve (the hotel above it);
        // a fully built town then pays for its stadium as fast as the old one did (the buildings with no mechanic yet aside)
        MEDIA_CENTER to listOf(
            L(14_000, 3 * H, stadium = 6, incomePerHour = 440, capHours = 10),
            L(24_000, 5 * H, stadium = 7, incomePerHour = 580, capHours = 10),
            L(40_000, 8 * H, stadium = 7, incomePerHour = 740, capHours = 11),
            L(66_000, 12 * H, stadium = 8, incomePerHour = 940, capHours = 11),
            L(105_000, 16 * H, stadium = 8, incomePerHour = 1_200, capHours = 12),
            L(165_000, 22 * H, stadium = 9, incomePerHour = 1_520, capHours = 12),
            L(260_000, 30 * H, stadium = 9, incomePerHour = 1_920, capHours = 13),
            L(400_000, 40 * H, stadium = 10, incomePerHour = 2_400, capHours = 13),
            L(610_000, 52 * H, stadium = 10, incomePerHour = 3_000, capHours = 14),
            L(900_000, 66 * H, stadium = 10, incomePerHour = 3_800, capHours = 14),
        ),
        SPONSOR_OFFICE to listOf(
            L(16_000, 3 * H, stadium = 6, incomePerHour = 520, capHours = 10),
            L(27_000, 5 * H, stadium = 7, incomePerHour = 660, capHours = 10),
            L(45_000, 8 * H, stadium = 7, incomePerHour = 860, capHours = 11),
            L(75_000, 12 * H, stadium = 8, incomePerHour = 1_100, capHours = 11),
            L(120_000, 16 * H, stadium = 8, incomePerHour = 1_400, capHours = 12),
            L(190_000, 22 * H, stadium = 9, incomePerHour = 1_780, capHours = 12),
            L(300_000, 30 * H, stadium = 9, incomePerHour = 2_260, capHours = 13),
            L(460_000, 40 * H, stadium = 10, incomePerHour = 2_860, capHours = 13),
            L(700_000, 52 * H, stadium = 10, incomePerHour = 3_600, capHours = 14),
            L(1_000_000, 66 * H, stadium = 10, incomePerHour = 4_500, capHours = 14),
        ),
        HOTEL to listOf(
            L(90_000, 10 * H, stadium = 8, incomePerHour = 1_300, capHours = 12),
            L(130_000, 13 * H, stadium = 9, incomePerHour = 1_640, capHours = 12),
            L(180_000, 17 * H, stadium = 9, incomePerHour = 2_000, capHours = 13),
            L(250_000, 22 * H, stadium = 9, incomePerHour = 2_400, capHours = 13),
            L(340_000, 28 * H, stadium = 9, incomePerHour = 2_900, capHours = 14),
            L(460_000, 34 * H, stadium = 10, incomePerHour = 3_400, capHours = 14),
            L(620_000, 41 * H, stadium = 10, incomePerHour = 4_000, capHours = 15),
            L(820_000, 49 * H, stadium = 10, incomePerHour = 4_600, capHours = 15),
            L(1_080_000, 57 * H, stadium = 10, incomePerHour = 5_300, capHours = 16),
            L(1_400_000, 66 * H, stadium = 10, incomePerHour = 6_000, capHours = 16),
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
        DECOR to listOf(L(200, 0)),
        // the hall of fame (the old trophy museum, its levels and effect): effect = exhibits on its shelves, effect2 =
        // atmosphere % to earning buildings around it
        HALL_OF_FAME to listOf(
            L(9_000, 4 * H, stadium = 3, effect = 6, effect2 = 2),
            L(25_000, 10 * H, stadium = 5, effect = 12, effect2 = 4),
            L(70_000, 20 * H, stadium = 6, effect = 20, effect2 = 6),
            L(180_000, 36 * H, stadium = 8, effect = 30, effect2 = 8),
            L(450_000, 60 * H, stadium = 10, effect = 45, effect2 = 10),
        ),
        // fences: drawn along the plot by dragging, instant to build and to upgrade (no builder) — a coin sink that
        // changes the look: wooden picket → hedge → brick → stone → steel → glass with an LED rail
        FENCE to listOf(L(30, 0), L(150, 0, stadium = 2), L(600, 0, stadium = 3), L(2_000, 0, stadium = 5), L(6_000, 0, stadium = 7), L(15_000, 0, stadium = 9)),
        // the town's homes: tax per hour, a few from the first stadium (the sport town), a level more with every stadium
        HOUSE to listOf(
            L(2_500, 20 * M, stadium = 1, incomePerHour = 60, capHours = 8),
            L(4_500, 40 * M, stadium = 2, incomePerHour = 80, capHours = 8),
            L(8_000, 90 * M, stadium = 3, incomePerHour = 110, capHours = 9),
            L(14_000, 3 * H, stadium = 4, incomePerHour = 150, capHours = 9),
            L(24_000, 5 * H, stadium = 5, incomePerHour = 200, capHours = 10),
            L(40_000, 8 * H, stadium = 6, incomePerHour = 260, capHours = 10),
            L(70_000, 12 * H, stadium = 7, incomePerHour = 340, capHours = 11),
            L(120_000, 18 * H, stadium = 8, incomePerHour = 440, capHours = 12),
            L(200_000, 26 * H, stadium = 9, incomePerHour = 570, capHours = 13),
            L(330_000, 36 * H, stadium = 10, incomePerHour = 740, capHours = 14),
        ),
        // the yard courts (the old parks): effect2 = atmosphere % to the earning buildings round them
        BACKYARD_COURT to listOf(
            L(5_000, 1 * H, stadium = 3, effect2 = 3),
            L(12_000, 3 * H, stadium = 4, effect2 = 4),
            L(25_000, 5 * H, stadium = 5, effect2 = 5),
            L(45_000, 8 * H, stadium = 6, effect2 = 6),
            L(80_000, 11 * H, stadium = 7, effect2 = 7),
            L(140_000, 15 * H, stadium = 7, effect2 = 8),
            L(240_000, 20 * H, stadium = 8, effect2 = 9),
            L(400_000, 28 * H, stadium = 9, effect2 = 10),
            L(620_000, 38 * H, stadium = 10, effect2 = 11),
            L(900_000, 48 * H, stadium = 10, effect2 = 12),
        ),
        // roads: drawn by dragging like fences, instant; a coin sink that joins the town up
        ROAD to listOf(L(40, 0, stadium = 4), L(200, 0, stadium = 7), L(800, 0, stadium = 10)),
    ) + SOON.associateWith { soonLevels(SOON_UNLOCK.getValue(it)) }

    // Footprints (cells of 10 m) are fixed for all ten levels and generous — the owner, 12 October: a building never
    // changes its plot, growth is in height and richness; the plots differ by type (the sport town's list, 14 October).
    // 14 October, pass 3 (the owner: «многие здания визуально выходят за рамки своей зоны, сетку сделай не такую мелкую»):
    // the build grid's tile is 2×2 cells (20 m, [Grid.TILE]) — every footprint a whole number of tiles, every building on
    // a tile ([Grid.aligned], the server checks it) — and each footprint's proportions come from its own ChatGPT drawing
    // (art/scripts/cut_sprites.py fits the drawing's ground inside the footprint's diamond and town_sprites.py lists the
    // fill each candidate gets): the ticket office square as drawn, the stadium the biggest — 16×16 since 16 October (the owner: the
    // stadium is the city's colossal centrepiece, make it bigger; 12×12 before; the island arenas went on 15 October); DECOR,
    // FENCE and ROAD one tile each. 16 October, later (the owner: next to the 16×16 stadium the buildings are far too small —
    // more cells, wider, much taller): most buildings a tile bigger each way where the island still holds the whole
    // showcase town (the academy 10×6, the training centre 12×8, the hall of fame, shopping centre and fan zone 8×8, the
    // others 8×6 or 6×6; the house, the school, the yard court and the transport hub as they were or near it), the
    // recovery centre and the coaching HQ merged away (V46), and to make room the houses 10 at the top (20), the ticket
    // offices 2 (3), the yard courts 2 (4) and the fences 20 (40); the roads 120 tiles (the ring round the bigger stadium
    // and its approaches take ~90, the boulevards to the bridges the rest). A stadium-10 town (every type at its count,
    // the decor, the fences and the roads at their allowance) covers ~77 % of the main island's tiles (2148 of 2780 cells;
    // land_mask c9c2cb41: 695 whole tiles; BuildingRulesTest keeps it under 80 %).
    val specs: Map<BuildingType, BuildingSpec> = (listOf(
        BuildingSpec(PITCH, 16, 16, true, levelTable.getValue(PITCH)),
        BuildingSpec(ACADEMY, 10, 6, true, levelTable.getValue(ACADEMY), List(10) { 1 }, CAP8),
        BuildingSpec(TRAINING_CENTER, 12, 8, true, levelTable.getValue(TRAINING_CENTER), List(10) { 1 }, CAP8),
        BuildingSpec(MEDICAL_CENTER, 8, 6, true, levelTable.getValue(MEDICAL_CENTER), countsFrom(0, 1, 1, 1, 1, 1, 1, 1, 1, 1), CAP8),
        BuildingSpec(TICKET_OFFICE, 6, 6, true, levelTable.getValue(TICKET_OFFICE), countsFrom(1, 1, 1, 2, 2, 2, 2, 2, 2, 2), CAP10),
        BuildingSpec(DECOR, 2, 2, true, levelTable.getValue(DECOR), List(10) { Stadium.decorCount(it + 1) }, List(10) { 1 }),
        BuildingSpec(FENCE, 2, 2, true, levelTable.getValue(FENCE), List(10) { Stadium.fenceCount(it + 1) }, listOf(1, 2, 3, 3, 4, 4, 5, 5, 6, 6)),
        BuildingSpec(HOUSE, 6, 4, true, levelTable.getValue(HOUSE), countsFrom(2, 3, 4, 5, 6, 7, 8, 9, 10, 10), CAP10),
        // in tiles since 14 October, pass 3 (one road tile is 2×2 cells): stadium 5: 80, the showcase's ring round the 8×8-tile stadium with its approaches is 80
        BuildingSpec(ROAD, 2, 2, true, levelTable.getValue(ROAD), countsFrom(0, 0, 0, 30, 90, 100, 105, 110, 115, 120), listOf(0, 0, 0, 1, 1, 1, 2, 2, 2, 3)),
        BuildingSpec(SHOPPING_CENTER, 8, 8, true, levelTable.getValue(SHOPPING_CENTER), countsFrom(0, 1, 1, 1, 2, 2, 2, 2, 2, 2), CAP10),
        BuildingSpec(HALL_OF_FAME, 8, 8, true, levelTable.getValue(HALL_OF_FAME), oneFrom(3), listOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5)),
        BuildingSpec(BACKYARD_COURT, 4, 4, true, levelTable.getValue(BACKYARD_COURT), countsFrom(0, 0, 1, 1, 1, 1, 2, 2, 2, 2), capsFrom(3)),
        BuildingSpec(FAN_ZONE, 8, 8, true, levelTable.getValue(FAN_ZONE), oneFrom(2), CAP10),
        BuildingSpec(MEDIA_CENTER, 8, 6, true, levelTable.getValue(MEDIA_CENTER), oneFrom(6), capsFrom(6)),
        BuildingSpec(SPONSOR_OFFICE, 6, 6, true, levelTable.getValue(SPONSOR_OFFICE), oneFrom(6), capsFrom(6)),
        BuildingSpec(HOTEL, 8, 6, true, levelTable.getValue(HOTEL), oneFrom(8), capsFrom(8)),
    ) + SOON.map { t -> val (w, h) = SOON_SIZE.getValue(t); BuildingSpec(t, w, h, true, levelTable.getValue(t), oneFrom(SOON_UNLOCK.getValue(t)), capsFrom(SOON_UNLOCK.getValue(t)), soon = true) })
        .associateBy { it.type }

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

/** The buildings with no mechanic yet (the sport town, 14 October), the stadium each opens at and its footprint. */
private val SOON = listOf(ATHLETES_RESIDENCE, YOUTH_SPORTS_SCHOOL, SPORTS_SCIENCE_LAB, SCHOOL, TRANSPORT_HUB, SCOUTING_OFFICE, ANALYTICS_CENTER)
private val SOON_UNLOCK = mapOf(ATHLETES_RESIDENCE to 2, YOUTH_SPORTS_SCHOOL to 3, SPORTS_SCIENCE_LAB to 4, SCHOOL to 4, TRANSPORT_HUB to 4,
    SCOUTING_OFFICE to 5, ANALYTICS_CENTER to 7)
private val SOON_SIZE = mapOf(ATHLETES_RESIDENCE to (8 to 6), YOUTH_SPORTS_SCHOOL to (8 to 6), SPORTS_SCIENCE_LAB to (8 to 6), SCHOOL to (6 to 6), TRANSPORT_HUB to (4 to 4),
    SCOUTING_OFFICE to (6 to 6), ANALYTICS_CENTER to (8 to 6))

object ClubLevels {
    private val thresholds = listOf(0, 100, 300, 700, 1_500, 3_000, 5_500, 9_000, 14_000, 20_000)
    const val MAX_LEVEL = 10
    fun levelFor(reputation: Int): Int = thresholds.indexOfLast { reputation >= it } + 1
    fun gridSize(level: Int): Int = Grid.PLOT   // the plot never grows, see Stadium.gridSize
    fun reputationForNext(level: Int): Int? = thresholds.getOrNull(level)
}

/**
 * The old square plot's size in cells, still sent as `gridSize` for one client release: the land is a mask since the
 * owner's picture (13 October, [Lands]); the client that knows the mask reads the mask.
 */
object Grid {
    const val PLOT = 48
    /** The build grid's tile in cells (14 October, pass 3): footprints are whole tiles, positions on tile corners. */
    const val TILE = 2
    /** A position on a tile corner (the API keeps cells; a building's top-left cell is even both ways). */
    fun aligned(x: Int, y: Int) = Math.floorMod(x, TILE) == 0 && Math.floorMod(y, TILE) == 0
}

data class Footprint(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun overlaps(o: Footprint) = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
}

fun footprint(type: BuildingType, x: Int, y: Int, rotation: Int): Footprint {
    val s = BuildingCatalog.spec(type)
    return if (rotation % 180 == 0) Footprint(x, y, s.width, s.height) else Footprint(x, y, s.height, s.width)
}
