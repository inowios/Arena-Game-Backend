package com.arenacity.domain.rules

/**
 * Long goals (GAME-PLAN phase 3).
 *
 * Roads: rewards laid along a scale, every next one visible (Clash Royale's trophy road). The trophy road pays out as the
 * arena trophies climb; the club-level road as the club levels up. A step is claimed once and stays claimed when the
 * trophies later drop. [RoadReward.pack]: a new player (quality of the arena the club stands in) when the squad has
 * room, [Cosmetics.DUPLICATE_GOLD]-style gold otherwise.
 */
enum class Road { TROPHY, LEVEL }

data class RoadReward(val coins: Long = 0, val gold: Int = 0, val item: String? = null, val pack: Boolean = false)
data class RoadStep(val road: Road, val index: Int, val at: Int, val reward: RoadReward)

object Roads {
    /** Gold instead of a pack when the squad is full. */
    const val PACK_FALLBACK_GOLD = 30

    private fun steps(road: Road, vararg s: Pair<Int, RoadReward>) = s.mapIndexed { i, (at, r) -> RoadStep(road, i + 1, at, r) }

    val TROPHY: List<RoadStep> = steps(Road.TROPHY,
        50 to RoadReward(coins = 300),
        100 to RoadReward(gold = 10),
        200 to RoadReward(coins = 800),
        300 to RoadReward(item = "decor_led_pole"),
        400 to RoadReward(coins = 1_500),
        500 to RoadReward(pack = true),
        700 to RoadReward(gold = 25),
        850 to RoadReward(coins = 4_000),
        1_000 to RoadReward(item = "frame_neon"),
        1_300 to RoadReward(pack = true),
        1_600 to RoadReward(coins = 10_000),
        1_900 to RoadReward(gold = 40),
        2_200 to RoadReward(item = "decor_holo_ball"),
        2_600 to RoadReward(coins = 25_000),
        3_000 to RoadReward(pack = true),
        3_500 to RoadReward(gold = 60),
        4_200 to RoadReward(coins = 60_000),
        5_000 to RoadReward(gold = 80),
        5_500 to RoadReward(item = "decor_drone_pad"),
        6_500 to RoadReward(pack = true),
        8_000 to RoadReward(item = "frame_aurora", gold = 150),
    )

    /** Reached at club level [at] (2..10). */
    val LEVEL: List<RoadStep> = steps(Road.LEVEL,
        2 to RoadReward(coins = 500, gold = 10),
        3 to RoadReward(coins = 1_200, item = "decor_bench"),
        4 to RoadReward(gold = 20),
        5 to RoadReward(coins = 3_000, pack = true),
        6 to RoadReward(gold = 30, item = "decor_lamp"),
        7 to RoadReward(coins = 8_000),
        8 to RoadReward(gold = 50, item = "frame_stars"),
        9 to RoadReward(coins = 20_000, pack = true),
        10 to RoadReward(gold = 100, item = "decor_fountain"),
    )

    fun of(road: Road) = if (road == Road.TROPHY) TROPHY else LEVEL
    fun step(road: Road, index: Int): RoadStep? = of(road).getOrNull(index - 1)
    /** The value the road is measured in: arena trophies or the club level. */
    fun progress(road: Road, club: com.arenacity.domain.model.Club) = if (road == Road.TROPHY) club.trophies else club.level
}

/**
 * Collections: sets of cosmetics. A complete set lifts the income of every earning building by its bonus, on top of
 * the atmosphere (not under its cap), and shows a badge in the profile.
 */
data class CollectionSet(val id: String, val items: List<String>, val incomePct: Int)

object Collections {
    val ALL = listOf(
        CollectionSet("park", listOf("decor_flowerbed", "decor_bench", "decor_hedge", "decor_lamp", "decor_fountain"), 4),
        CollectionSet("classic_kits", listOf("kit_stripes", "kit_hoops", "kit_halves", "kit_sash"), 3),
        CollectionSet("frames", listOf("frame_silver", "frame_stars", "frame_flame"), 2),
        CollectionSet("celebrations", listOf("cel_slide", "cel_aeroplane", "cel_backflip"), 2),
        CollectionSet("legends", listOf("decor_trophy", "frame_laurel", "frame_legend", "kit_retro", "cel_bow"), 4),
        CollectionSet("future", listOf("decor_led_pole", "decor_holo_ball", "decor_drone_pad", "frame_neon", "frame_aurora"), 5),
    )
    fun complete(owned: Set<String>) = ALL.filter { s -> s.items.all { it in owned } }
    fun incomePct(owned: Set<String>) = complete(owned).sumOf { it.incomePct }
}

/**
 * The trophy museum (А11), the HALL_OF_FAME since the sport town: a building whose shelves hold the club's honours — every achievement tier reached, league
 * titles, cups, the highest arena. Its level is how many exhibits fit ([MuseumRules.capacity]); visitors draw a crowd
 * that lifts the earning buildings around it ([MuseumRules.atmospherePct] within [MuseumRules.RANGE] cells).
 */
object MuseumRules {
    const val RANGE = 3
    // ten levels since 16 October (V47 doubled the built ones: the old level k gives what the new 2k gives)
    fun capacity(level: Int) = listOf(0, 3, 6, 9, 12, 16, 20, 25, 30, 37, 45)[level.coerceIn(0, 10)]
    fun atmospherePct(level: Int) = level.coerceIn(0, 10)
}

/**
 * Season themes (В5): every pass season wears the look of the time of year — its colours on the pass screen and an
 * exclusive decoration handed out on the pass at [ITEM_LEVEL] (free track), only while that theme runs.
 */
data class SeasonTheme(val id: String, val accent: String, val accent2: String, val item: String)

object SeasonThemes {
    const val ITEM_LEVEL = 20
    val AUTUMN = SeasonTheme("autumn", "#E8772E", "#6B3E1F", "decor_pumpkins")
    val WINTER = SeasonTheme("winter", "#3FA7E0", "#FFFFFF", "decor_snow_tree")
    val SPRING = SeasonTheme("spring", "#F28DB2", "#7CC46B", "decor_blossom")
    val SUMMER = SeasonTheme("summer", "#F7C531", "#2EB5C8", "decor_beach")
    val ALL = listOf(AUTUMN, WINTER, SPRING, SUMMER)
    fun of(date: java.time.LocalDate): SeasonTheme = when (date.monthValue) { 9, 10, 11 -> AUTUMN; 12, 1, 2 -> WINTER; 3, 4, 5 -> SPRING; else -> SUMMER }
}
