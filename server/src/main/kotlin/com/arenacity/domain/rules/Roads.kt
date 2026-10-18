package com.arenacity.domain.rules

/**
 * Long goals (GAME-PLAN phase 3).
 *
 * Roads: rewards laid along a scale, every next one visible (Clash Royale's trophy road). The trophy road pays out as the
 * arena trophies climb; the club-level road as the club levels up. A step is claimed once and stays claimed when the
 * trophies later drop. [RoadReward.pack]: a new player (no step gives one since heroes replaced cards and packs, 18 October
 * 2026: gold instead); a town decoration neither (they went with the city builder).
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
        300 to RoadReward(coins = 1_000, gold = 5),
        400 to RoadReward(coins = 1_500),
        500 to RoadReward(gold = 30),
        700 to RoadReward(gold = 25),
        850 to RoadReward(coins = 4_000),
        1_000 to RoadReward(item = "frame_neon"),
        1_300 to RoadReward(gold = 40),
        1_600 to RoadReward(coins = 10_000),
        1_900 to RoadReward(gold = 40),
        2_200 to RoadReward(item = "kit_halves", gold = 20),
        2_600 to RoadReward(coins = 25_000),
        3_000 to RoadReward(gold = 60),
        3_500 to RoadReward(gold = 60),
        4_200 to RoadReward(coins = 60_000),
        5_000 to RoadReward(gold = 80),
        5_500 to RoadReward(item = "cel_aeroplane", gold = 40),
        6_500 to RoadReward(gold = 100),
        8_000 to RoadReward(item = "frame_aurora", gold = 150),
    )

    /** Reached at club level [at] (2..10). */
    val LEVEL: List<RoadStep> = steps(Road.LEVEL,
        2 to RoadReward(coins = 500, gold = 10),
        3 to RoadReward(coins = 1_500, gold = 5),
        4 to RoadReward(gold = 20),
        5 to RoadReward(coins = 3_000, gold = 30),
        6 to RoadReward(gold = 40),
        7 to RoadReward(coins = 8_000),
        8 to RoadReward(gold = 50, item = "frame_stars"),
        9 to RoadReward(coins = 20_000, gold = 50),
        10 to RoadReward(gold = 150, item = "frame_gold"),
    )

    fun of(road: Road) = if (road == Road.TROPHY) TROPHY else LEVEL
    fun step(road: Road, index: Int): RoadStep? = of(road).getOrNull(index - 1)
    /** The value the road is measured in: arena trophies or the club level. */
    fun progress(road: Road, club: com.arenacity.domain.model.Club) = if (road == Road.TROPHY) club.trophies else club.level
}

/**
 * Collections: sets of cosmetics. A complete set shows a badge in the profile; [CollectionSet.incomePct] lifted the
 * buildings' income, which went with the city builder (17 October 2026): it is still sent, it acts on nothing for now.
 */
data class CollectionSet(val id: String, val items: List<String>, val incomePct: Int)

object Collections {
    val ALL = listOf(
        CollectionSet("classic_kits", listOf("kit_stripes", "kit_hoops", "kit_halves", "kit_sash"), 3),
        CollectionSet("frames", listOf("frame_silver", "frame_stars", "frame_flame"), 2),
        CollectionSet("celebrations", listOf("cel_slide", "cel_aeroplane", "cel_backflip"), 2),
        CollectionSet("legends", listOf("cel_backflip", "frame_laurel", "frame_legend", "kit_retro", "cel_bow"), 4),
        CollectionSet("future", listOf("frame_neon", "frame_aurora", "kit_halves", "cel_aeroplane"), 5),
    )
    fun complete(owned: Set<String>) = ALL.filter { s -> s.items.all { it in owned } }
    fun incomePct(owned: Set<String>) = complete(owned).sumOf { it.incomePct }
}

/**
 * The hall of fame (the trophy museum, А11): the shelves of the club's honours — every achievement tier reached, league
 * titles, cups, the highest arena. No building since the five islands map (17 October 2026): it belongs to the stadium,
 * and the stadium level (the club's open spots, rules.MapSpots) is how many exhibits fit ([capacity]).
 */
object MuseumRules {
    fun capacity(stadium: Int) = listOf(0, 3, 6, 9, 12, 16, 20, 25, 30, 37, 45)[stadium.coerceIn(0, 10)]
}

/**
 * Season themes (В5): every pass season wears the look of the time of year — its colours on the pass screen and an
 * exclusive decoration handed out on the pass at [ITEM_LEVEL] (free track), only while that theme runs. No item since the
 * town decorations went with the city builder (17 October 2026): [item] is empty, the pass level gives its usual reward.
 */
data class SeasonTheme(val id: String, val accent: String, val accent2: String, val item: String)

object SeasonThemes {
    const val ITEM_LEVEL = 20
    val AUTUMN = SeasonTheme("autumn", "#E8772E", "#6B3E1F", "")
    val WINTER = SeasonTheme("winter", "#3FA7E0", "#FFFFFF", "")
    val SPRING = SeasonTheme("spring", "#F28DB2", "#7CC46B", "")
    val SUMMER = SeasonTheme("summer", "#F7C531", "#2EB5C8", "")
    val ALL = listOf(AUTUMN, WINTER, SPRING, SUMMER)
    fun of(date: java.time.LocalDate): SeasonTheme = when (date.monthValue) { 9, 10, 11 -> AUTUMN; 12, 1, 2 -> WINTER; 3, 4, 5 -> SPRING; else -> SUMMER }
}
