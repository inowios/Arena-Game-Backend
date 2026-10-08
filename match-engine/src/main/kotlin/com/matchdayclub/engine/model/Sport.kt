package com.matchdayclub.engine.model

import kotlinx.serialization.Serializable

/**
 * Everything that differs between sports. The simulator itself is sport-agnostic.
 * @param possessions number of possession cycles per match
 * @param baseChanceRate probability a possession produces a scoring chance for equal teams
 * @param baseConversion probability a chance becomes a score for equal teams
 */
@Serializable
data class SportProfile(
    val id: String,
    val startersCount: Int,
    val possessions: Int,
    val baseChanceRate: Double,
    val baseConversion: Double,
    val scoreName: String,
    val durationMinutes: Int,
    val homeAdvantage: Double,
    val maxSubs: Int,
)

val FOOTBALL = SportProfile(
    id = "football", startersCount = 11, possessions = 120, baseChanceRate = 0.20, baseConversion = 0.11,
    scoreName = "goal", durationMinutes = 90, homeAdvantage = 0.06, maxSubs = 5,
)
