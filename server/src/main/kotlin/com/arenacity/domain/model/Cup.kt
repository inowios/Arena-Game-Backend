package com.arenacity.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class CupStatus { ACTIVE, FINISHED }

/** One weekly knockout cup of a timezone bucket; [tier] 1 = the strongest group of that week. */
data class Cup(
    val id: UUID,
    val tzBucket: Int,
    val weekStart: LocalDate,
    val tier: Int,
    val division: Int,
    val currentRound: Int,
    val status: CupStatus,
    val winnerClubId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Local zone of the cup (most common among its human entrants); null = the bucket's whole-hour offset. */
    val timezone: String? = null,
)

/** A club's place in a week's cup: [outRound] is the round it lost in (null while alive, or as champion). */
data class CupEntry(
    val weekStart: LocalDate,
    val clubId: UUID,
    val cupId: UUID,
    val tzBucket: Int,
    val seed: Int,
    val outRound: Int? = null,
    val prizeCoins: Long = 0,
    val prizeGold: Int = 0,
    val prizeReputation: Int = 0,
    val createdAt: Instant,
)

data class CupTie(val cupId: UUID, val round: Int, val slot: Int, val homeClubId: UUID, val awayClubId: UUID, val fixtureId: UUID, val winnerClubId: UUID? = null)
