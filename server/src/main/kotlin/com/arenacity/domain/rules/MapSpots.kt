package com.arenacity.domain.rules

import com.arenacity.domain.model.People

/**
 * One painted building on the five islands map (the owner, 17 October 2026: the free-form city builder went, the world is
 * a fixed painting of five islands; the old builder lives on in the branch `saved/city-builder-levels`). [island]: "main"
 * or the id of a people's island ("beast", "stone", "android", "shadow", [People.island]); [cost]: the coins that open a
 * main-island spot (0 for the stadium, always open), for an island spot its zone's price ([Zones]) — an island spot opens
 * with its island (the zone OPEN), never on its own; [people]: the people whose island it stands on, null on the main island.
 */
data class MapSpot(val id: String, val island: String, val cost: Long, val people: People? = null)

/**
 * The map's ten spots and what the club's stadium level gives. The stadium level is the number of open spots (1..10):
 * the stadium is always open, the main island's five open for coins in any order, each people's island spot opens with
 * its zone. So the beastfolk's island (stadium 4) needs the stadium and three main spots, the androids' (7) more, the
 * shadows' (10) everything else open.
 *
 * The tables below are the old buildings' level tables (the stadium's attribute cap and seats, the training centre's
 * slots and gain, the youth school's graduates, the medical centre's cooldown), indexed by the stadium level now.
 */
object MapSpots {
    const val MAX_STADIUM = 10
    const val MAIN = "main"

    const val STADIUM = "stadium"
    const val ACADEMY = "academy"
    const val HOSPITAL = "hospital"
    const val MALL = "mall"
    const val LIGHTHOUSE = "lighthouse"
    const val MARINA = "marina"

    /** The spot on each people's island (the client's ids). */
    private val ISLAND_SPOTS = mapOf(People.ANDROID to "android_stadium", People.STONE to "stone_arena", People.BEAST to "beast_dome", People.SHADOW to "shadow_ruins")

    val ALL: List<MapSpot> = listOf(
        MapSpot(STADIUM, MAIN, 0),
        MapSpot(ACADEMY, MAIN, 3_000),
        MapSpot(HOSPITAL, MAIN, 6_000),
        MapSpot(MALL, MAIN, 12_000),
        MapSpot(LIGHTHOUSE, MAIN, 25_000),
        MapSpot(MARINA, MAIN, 50_000),
    ) + listOf(People.ANDROID, People.STONE, People.BEAST, People.SHADOW).map { p -> MapSpot(ISLAND_SPOTS.getValue(p), p.island, Zones.spec(p).priceCoins, p) }

    private val byId = ALL.associateBy { it.id }

    fun find(id: String): MapSpot? = byId[id.trim().lowercase()]

    /** The main-island spots bought with coins (all but the stadium). */
    val BUYABLE: List<MapSpot> = ALL.filter { it.island == MAIN && it.cost > 0 }

    /** The spot on [people]'s island. */
    fun islandSpot(people: People): MapSpot = byId.getValue(ISLAND_SPOTS.getValue(people))

    /** The open spots, in [ALL]'s order: the stadium, the [unlocked] main spots, and the spots of the [open] peoples' islands. */
    fun open(unlocked: Set<String>, open: Set<People>): List<String> =
        ALL.filter { s -> s.id == STADIUM || (s.people == null && s.id in unlocked) || (s.people != null && s.people in open) }.map { it.id }

    /** The stadium level: the number of open spots. */
    fun stadiumLevel(openSpots: Int): Int = openSpots.coerceIn(1, MAX_STADIUM)

    private fun at(table: List<Int>, stadium: Int) = table[stadium.coerceIn(1, MAX_STADIUM) - 1]

    private val ATTRIBUTE_CAP = listOf(60, 64, 68, 72, 76, 80, 84, 88, 93, 99)
    private val SEATS = listOf(500, 1_200, 2_500, 4_000, 6_000, 8_500, 11_500, 15_000, 20_000, 26_000)
    private val TRAINING_SLOTS = listOf(1, 1, 2, 2, 3, 3, 4, 4, 5, 5)
    private val TRAINING_DELTA = listOf(1, 2, 2, 2, 3, 3, 3, 4, 4, 5)
    private val GRADUATE_LOW = listOf(40, 44, 48, 52, 57, 62, 67, 72, 76, 80)
    private val GRADUATE_HOURS = listOf(12, 11, 10, 9, 8, 8, 7, 7, 6, 6)
    private val MEDICAL_HOURS = listOf(5, 4, 4, 3, 3, 3, 2, 2, 1, 1)

    /** The highest an athlete's attribute grows (training, matches) at [stadium]. */
    fun attributeCap(stadium: Int) = at(ATTRIBUTE_CAP, stadium)
    /** The seats of the home stadium at [stadium] (the crowd the device draws). */
    fun seats(stadium: Int) = at(SEATS, stadium)
    fun trainingSlots(stadium: Int) = at(TRAINING_SLOTS, stadium)
    fun trainingDelta(stadium: Int) = at(TRAINING_DELTA, stadium)
    /** The lowest quality of an academy graduate; the highest is 15 above. */
    fun graduateLow(stadium: Int) = at(GRADUATE_LOW, stadium)
    fun graduateHours(stadium: Int) = at(GRADUATE_HOURS, stadium)
    /** Hours the hospital rests after a treatment. */
    fun medicalHours(stadium: Int) = at(MEDICAL_HOURS, stadium)
}
