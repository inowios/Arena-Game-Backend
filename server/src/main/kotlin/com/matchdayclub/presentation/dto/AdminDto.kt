package com.matchdayclub.presentation.dto

import com.matchdayclub.domain.model.MetricsReport
import com.matchdayclub.domain.usecase.admin.SupportCard
import kotlinx.serialization.Serializable

@Serializable data class ClientEventDto(val name: String, val props: Map<String, String>? = null, val at: Long? = null)
@Serializable data class ClientEventsRequest(val platform: String? = null, val appVersion: String? = null, val events: List<ClientEventDto>)
@Serializable data class ClientEventsResponse(val accepted: Int)

@Serializable data class CohortDto(val day: String, val installs: Int, val d1: Int, val d7: Int)
/** A KPI with the spec's goal and the "hook doesn't work" threshold (§1). */
@Serializable data class KpiDto(val value: Double?, val target: Double, val threshold: Double, val status: String)
@Serializable data class MetricsReportDto(
    val from: String, val to: String,
    val kpis: Map<String, KpiDto>,
    val dau: Int, val wau: Int, val mau: Int, val activeInPeriod: Int,
    val activeLast7: Int, val inPrivateLeague: Int, val invitedNewPlayers: Int,
    val installs: Int, val cohorts: List<CohortDto>,
    val purchases: Map<String, Int>, val adRewards: Int, val events: Map<String, Int>,
)

private fun kpi(value: Double?, target: Double, threshold: Double) =
    KpiDto(value, target, threshold, when { value == null -> "NO_DATA"; value >= target -> "ON_TARGET"; value < threshold -> "BELOW_THRESHOLD"; else -> "BELOW_TARGET" })

fun MetricsReport.toDto() = MetricsReportDto(
    from.toString(), to.toString(),
    linkedMapOf(
        "d1Retention" to kpi(d1, 0.40, 0.30), "d7Retention" to kpi(d7, 0.20, 0.12),
        "privateLeagueShare" to kpi(privateLeagueShare, 0.30, 0.15), "kFactor" to kpi(kFactor, 0.30, 0.10),
    ),
    dau, wau, mau, activeInPeriod, activeLast7, inPrivateLeague, invitedNewPlayers,
    cohorts.sumOf { it.installs }, cohorts.map { CohortDto(it.day.toString(), it.installs, it.d1, it.d7) },
    purchases, adRewards, events,
)

@Serializable data class SchedulerStatusDto(val lastTickAt: String?, val lastMatches: Int, val lastCupMatches: Int, val lastError: String?, val ticks: Long, val failures: Long)
@Serializable data class OpsStatsDto(val counts: Map<String, Long>, val scheduler: SchedulerStatusDto, val version: String)

@Serializable data class AdminClubDto(val id: String, val name: String, val userId: String, val isBot: Boolean, val division: Int, val level: Int, val reputation: Int, val coins: Long, val gold: Int, val timezone: String, val createdAt: String)
@Serializable data class AdminUserDto(val id: String, val provider: String, val email: String?, val locale: String?, val isActive: Boolean, val createdAt: String)
@Serializable data class AdminPurchaseDto(val store: String, val productId: String, val externalId: String, val status: String, val gold: Int, val coins: Long, val createdAt: String)
@Serializable data class SupportCardDto(val club: AdminClubDto, val user: AdminUserDto?, val purchases: List<AdminPurchaseDto>, val lastActive: String?)
@Serializable data class GrantRequest(val coins: Long = 0, val gold: Int = 0, val reason: String)
@Serializable data class SchedulerRunResponse(val matches: Int, val cupMatches: Int)

fun com.matchdayclub.domain.model.Club.toAdminDto() = AdminClubDto(id.toString(), name, userId.toString(), isBot, division, level, reputation, coins, gold, timezone, createdAt.toString())

fun SupportCard.toDto() = SupportCardDto(
    club.toAdminDto(),
    user?.let { AdminUserDto(it.id.toString(), it.authProvider.name, it.email, it.locale, it.isActive, it.createdAt.toString()) },
    purchases.map { AdminPurchaseDto(it.store, it.productId, it.externalId, it.status, it.grantedGold, it.grantedCoins, it.createdAt.toString()) },
    lastActive?.toString(),
)
