package com.arenacity.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One product event (app-reported or server-side). [props] are short strings. */
data class AnalyticsEvent(
    val userId: UUID?,
    val name: String,
    val props: Map<String, String> = emptyMap(),
    val platform: String? = null,
    val appVersion: String? = null,
    val at: Instant,
)

/** Installs of one signup day (UTC) and how many of them came back on day 1 and day 7. */
data class CohortRow(val day: LocalDate, val installs: Int, val d1: Int, val d7: Int)

/**
 * The launch KPIs from the design spec (§1): D1 40% / D7 20% retention, 30% of players in a private league,
 * k-factor 0.3 invited players per active player — plus activity, revenue and event counts for the same period.
 */
data class MetricsReport(
    val from: LocalDate,
    val to: LocalDate,
    val cohorts: List<CohortRow>,
    /** Over cohorts old enough to have the day (null when there is none yet). */
    val d1: Double?,
    val d7: Double?,
    val dau: Int,
    val wau: Int,
    val mau: Int,
    val activeInPeriod: Int,
    /** Players active in the 7 days up to [to] and how many of them are in at least one private league. */
    val activeLast7: Int,
    val inPrivateLeague: Int,
    val privateLeagueShare: Double?,
    /** New players who joined through an invite (league code or challenge link) during the period. */
    val invitedNewPlayers: Int,
    val kFactor: Double?,
    val purchases: Map<String, Int>,
    val adRewards: Int,
    val events: Map<String, Int>,
)

/** One human club's economy at report time (balance dashboard). [stadium] is its stadium level (open spots). */
data class ClubSnapshot(val coins: Long, val gold: Int, val trophies: Int, val level: Int, val stadium: Int)
