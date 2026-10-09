package com.matchdayclub.domain.model

import java.time.Instant
import java.util.UUID

enum class LiveStatus { OPEN, FINISHED, ABANDONED }

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
