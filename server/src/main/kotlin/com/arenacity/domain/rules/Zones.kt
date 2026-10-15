package com.arenacity.domain.rules

import com.arenacity.domain.model.People

/**
 * The bridge to a people's island (the owner, 15 October 2026: the side islands hold no arenas any more, each is the home
 * of a people — rules.Peoples): what it costs, the stadium it needs and how long it is built. A club whose bridge stands
 * has the island open: its dome goes, its people has returned and its land is the club's to build on.
 *
 * The prices and the building times are the zones' of the sport town (14 October: each island kept its own), the stadium
 * each needs follows the people's era (docs/claude/CARD-GAME-ROSTER-PROGRESSION.md §5, eras = stadium 1–3 / 4–6 / 7–9 /
 * 10): the beastfolk (south, was basketball) 4, the stone people (east, was volleyball) 5, the androids (north, was
 * tennis) 7, the shadows (west, was padel) 10. A club that opened an island earlier (at the sport town's lower stadium)
 * keeps it: only a new bridge checks the stadium. Hockey's zone (no island) went with V44.
 */
data class ZoneSpec(val people: People, val priceCoins: Long, val requiredStadium: Int, val restoreSeconds: Long)

object Zones {
    private const val H = 3600L
    val specs: Map<People, ZoneSpec> = listOf(
        ZoneSpec(People.BEAST, 8_000, 4, 2 * H),
        ZoneSpec(People.STONE, 50_000, 5, 12 * H),
        ZoneSpec(People.ANDROID, 40_000, 7, 6 * H),
        ZoneSpec(People.SHADOW, 120_000, 10, 12 * H),
    ).associateBy { it.people }

    fun spec(people: People) = specs.getValue(people)
}
