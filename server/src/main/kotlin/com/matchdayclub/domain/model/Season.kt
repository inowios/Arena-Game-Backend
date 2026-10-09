package com.matchdayclub.domain.model

import java.time.Instant
import java.util.UUID

enum class Movement { PROMOTED, RELEGATED, STAYED }
enum class TrophyKind { LEAGUE_TITLE, PROMOTION, CUP }

/** One finished season of one club in one public league (history + prizes). */
data class ClubSeason(
    val id: UUID,
    val clubId: UUID,
    val leagueId: UUID,
    val seasonNo: Int,
    val division: Int,
    val position: Int,
    val played: Int, val won: Int, val drawn: Int, val lost: Int,
    val gf: Int, val ga: Int, val points: Int,
    val movement: Movement,
    val newDivision: Int,
    val prizeCoins: Long,
    val prizeGold: Int,
    val prizeReputation: Int,
    val finishedAt: Instant,
)

data class Trophy(val id: UUID, val clubId: UUID, val kind: TrophyKind, val division: Int, val seasonNo: Int, val createdAt: Instant)
