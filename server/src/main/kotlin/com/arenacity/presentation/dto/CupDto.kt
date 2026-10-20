package com.arenacity.presentation.dto

import com.arenacity.domain.model.MatchdayResult
import com.arenacity.domain.rules.Cups
import com.arenacity.domain.usecase.cup.CupView
import kotlinx.serialization.Serializable

@Serializable data class ShootoutDto(val home: Int, val away: Int)
@Serializable data class CupTieDto(
    val round: Int, val slot: Int, val home: ClubRefDto, val away: ClubRefDto, val fixtureId: String, val status: String, val scheduledAt: String,
    val homeScore: Int? = null, val awayScore: Int? = null, val homePoints: Int? = null, val awayPoints: Int? = null,
    /** Always false / null since the Arena matches (20 October 2026): kept for one client version. */
    val multi: Boolean = false, val shootout: ShootoutDto? = null, val winnerId: String? = null,
)
@Serializable data class CupDto(val id: String, val tier: Int, val division: Int, val weekStart: String, val currentRound: Int, val rounds: Int, val status: String, val winnerId: String?, val nextRoundAt: String?)
@Serializable data class CupPrizeDto(val stage: String, val coins: Long, val gold: Int, val reputation: Int)
/** [stage]: ROUND_OF_16 … CHAMPION once decided (null while still in). */
@Serializable data class CupMeDto(val alive: Boolean, val outRound: Int?, val stage: String?, val prizeCoins: Long, val prizeGold: Int)
@Serializable data class CupPastDto(val weekStart: String, val stage: String, val prizeCoins: Long, val prizeGold: Int)
@Serializable data class CupViewDto(
    val state: String, val weekStart: String, val drawAt: String, val roundTimes: List<String>,
    val cup: CupDto?, val ties: List<CupTieDto>, val me: CupMeDto?, val prizes: List<CupPrizeDto>, val previous: CupPastDto?,
)

fun CupView.toDto(): CupViewDto {
    val c = cup
    val finished = c?.status?.name == "FINISHED"
    val meDto = me?.let { e ->
        val decided = e.outRound != null || (finished && c?.winnerClubId == e.clubId)
        CupMeDto(e.outRound == null && !finished, e.outRound, if (decided) Cups.stage(e.outRound).name else null, e.prizeCoins, e.prizeGold)
    }
    return CupViewDto(
        state, weekStart.toString(), drawAt.toString(), roundTimes.map { it.toString() },
        c?.let { CupDto(it.id.toString(), it.tier, it.division, it.weekStart.toString(), it.currentRound, Cups.ROUNDS, it.status.name, it.winnerClubId?.toString(),
            roundTimes.getOrNull(it.currentRound)?.takeIf { _ -> it.status.name == "ACTIVE" }?.toString()) },
        ties.map { t ->
            val f = fixtures[t.fixtureId]
            CupTieDto(t.round, t.slot, clubs.ref(t.homeClubId), clubs.ref(t.awayClubId), t.fixtureId.toString(), f?.status?.name ?: "SCHEDULED", (f?.scheduledAt ?: roundTimes[t.round - 1]).toString(),
                f?.homeScore, f?.awayScore, f?.homePoints, f?.awayPoints, false, null, t.winnerClubId?.toString())
        },
        meDto,
        Cups.Stage.values().map { s -> Cups.prize(s, division).let { p -> CupPrizeDto(s.name, p.coins, p.gold, p.reputation) } },
        previous?.let { CupPastDto(it.weekStart.toString(), Cups.stage(it.outRound).name, it.prizeCoins, it.prizeGold) },
    )
}
