package com.arenacity.domain.rules

/** The club's level by its reputation (1..10). */
object ClubLevels {
    private val thresholds = listOf(0, 100, 300, 700, 1_500, 3_000, 5_500, 9_000, 14_000, 20_000)
    const val MAX_LEVEL = 10
    fun levelFor(reputation: Int): Int = thresholds.indexOfLast { reputation >= it } + 1
    fun reputationForNext(level: Int): Int? = thresholds.getOrNull(level)
}
