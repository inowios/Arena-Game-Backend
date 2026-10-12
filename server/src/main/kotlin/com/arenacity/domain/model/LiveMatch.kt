package com.arenacity.domain.model

import java.time.Instant
import java.util.UUID

/** CANCELLED: given back before the kick-off ("another opponent") — no result, no penalty. */
enum class LiveStatus { OPEN, FINISHED, ABANDONED, CANCELLED }

/** A live match handed to a device: who plays whom with which starters; the result once reported. */
data class LiveMatch(
    val id: UUID,
    val clubId: UUID,
    val opponentId: UUID,
    val seed: Long,
    val status: LiveStatus,
    val starters: List<UUID>,
    val oppStarters: List<UUID>,
    val tempo: Double,
    val myOverall: Int,
    val oppOverall: Int,
    val createdAt: Instant,
    val homeScore: Int? = null,
    val awayScore: Int? = null,
    val resultJson: String? = null,
    val trophiesDelta: Int? = null,
    val finishedAt: Instant? = null,
)
