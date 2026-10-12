package com.arenacity.presentation.dto

import com.arenacity.domain.usecase.analytics.BalanceReport
import com.arenacity.domain.usecase.analytics.FunnelReport
import com.arenacity.domain.usecase.analytics.Spread
import kotlinx.serialization.Serializable

@Serializable data class FunnelStepDto(val step: String, val users: Int, val share: Double?, val fromPrevious: Double?, val medianSeconds: Long?, val putOff: Int, val skippedHere: Int)
@Serializable data class FunnelReportDto(val from: String, val to: String, val cohort: Int, val steps: List<FunnelStepDto>, val skipped: Int, val finished: Int)

fun FunnelReport.toDto() = FunnelReportDto(from.toString(), to.toString(), cohort,
    steps.map { FunnelStepDto(it.step, it.users, it.share, it.fromPrevious, it.medianSeconds, it.putOff, it.skippedHere) }, skipped, finished)

@Serializable data class SpreadDto(val p10: Long, val p50: Long, val p90: Long, val max: Long)
@Serializable data class LiveBalanceDto(val kickoffs: Int, val finishes: Int, val finishRate: Double?, val outcomes: Map<String, Int>, val controls: Map<String, Int>, val vsBots: Int)
@Serializable data class BalanceReportDto(
    val from: String, val to: String, val live: LiveBalanceDto, val obstaclesCleared: Int, val obstacleGold: Long,
    val purchases: Map<String, Int>, val purchaseStarts: Map<String, Int>, val adRewards: Int, val stadiumUps: Map<String, Int>,
    val activeClubs: Int, val coins: SpreadDto?, val gold: SpreadDto?, val trophies: SpreadDto?, val clubLevel: SpreadDto?,
    val stadium: Map<String, Int>, val buildings: Map<String, Map<String, Int>>,
)

private fun Spread.toDto() = SpreadDto(p10, p50, p90, max)

fun BalanceReport.toDto() = BalanceReportDto(
    from.toString(), to.toString(),
    LiveBalanceDto(live.kickoffs, live.finishes, live.finishRate, live.outcomes, live.controls, live.vsBots),
    obstaclesCleared, obstacleGold, purchases, purchaseStarts, adRewards, stadiumUps.mapKeys { it.key.toString() },
    activeClubs, coins?.toDto(), gold?.toDto(), trophies?.toDto(), clubLevel?.toDto(),
    stadium.mapKeys { it.key.toString() }, buildings.mapValues { (_, v) -> v.mapKeys { it.key.toString() } },
)
