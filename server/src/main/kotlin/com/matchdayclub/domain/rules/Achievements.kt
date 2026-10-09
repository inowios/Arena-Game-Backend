package com.matchdayclub.domain.rules

/**
 * What achievements measure. Counted metrics are lifetime counters fed by gameplay events (or a running maximum,
 * BEST_STREAK); state metrics are read from the club when the list is shown, so they never drift.
 */
enum class Metric(val counted: Boolean) {
    MATCHES_WON(true), SECTIONS_WON(true), TRAININGS(true), INCOME_COLLECTIONS(true), ACADEMY_GRADUATES(true),
    FRIENDLIES(true), INVITES(true), BEST_STREAK(true),
    CLUB_LEVEL(false), DIVISION(false), TITLES(false), PROMOTIONS(false), BUILDINGS_MAXED(false), SECTIONS_OPEN(false), CUPS(false),
}

/** Tiered achievement: reaching tiers[i] pays gold[i] (+ an achievement cosmetic for some tiers, see [Cosmetics]). */
data class Achievement(val id: String, val metric: Metric, val tiers: List<Int>, val gold: List<Int>) {
    init { require(tiers.size == gold.size && tiers.zipWithNext().all { (a, b) -> a < b }) }
    /** Number of tiers reached at [value] (0..tiers.size). */
    fun reached(value: Long) = tiers.count { value >= it }
}

object Achievements {
    val ALL: List<Achievement> = listOf(
        Achievement("winner", Metric.MATCHES_WON, listOf(1, 10, 50, 150, 400), listOf(5, 10, 25, 40, 80)),
        Achievement("all_rounder", Metric.SECTIONS_WON, listOf(5, 25, 100, 300), listOf(5, 15, 30, 60)),
        Achievement("climber", Metric.DIVISION, listOf(2, 3, 4, 5, 6, 7), listOf(15, 25, 40, 60, 90, 150)),
        Achievement("champion", Metric.TITLES, listOf(1, 3, 10), listOf(50, 100, 250)),
        Achievement("promoted", Metric.PROMOTIONS, listOf(1, 3, 6), listOf(20, 40, 80)),
        Achievement("cup_winner", Metric.CUPS, listOf(1, 3, 10), listOf(30, 80, 200)),
        Achievement("growing_club", Metric.CLUB_LEVEL, listOf(3, 5, 7, 10), listOf(10, 25, 50, 100)),
        Achievement("master_builder", Metric.BUILDINGS_MAXED, listOf(1, 3, 6, 10), listOf(10, 20, 40, 80)),
        Achievement("multisport", Metric.SECTIONS_OPEN, listOf(2, 3), listOf(20, 40)),
        Achievement("coach", Metric.TRAININGS, listOf(5, 50, 250, 1000), listOf(5, 15, 30, 60)),
        Achievement("tycoon", Metric.INCOME_COLLECTIONS, listOf(10, 100, 500, 2000), listOf(5, 15, 30, 60)),
        Achievement("talent_spotter", Metric.ACADEMY_GRADUATES, listOf(1, 10, 50), listOf(5, 15, 40)),
        Achievement("friendly_face", Metric.FRIENDLIES, listOf(1, 20, 100), listOf(5, 15, 40)),
        Achievement("loyal", Metric.BEST_STREAK, listOf(7, 30, 100), listOf(10, 40, 120)),
        Achievement("social", Metric.INVITES, listOf(1, 5, 20), listOf(20, 60, 200)),
    )

    private val byId = ALL.associateBy { it.id }
    fun find(id: String): Achievement? = byId[id]

    /** Cosmetic granted with [tier] (1-based) of achievement [id], if any. */
    fun item(id: String, tier: Int): Cosmetic? = Cosmetics.ALL.firstOrNull { it.source == CosmeticSource.ACHIEVEMENT && it.unlock == "$id:$tier" }
}
