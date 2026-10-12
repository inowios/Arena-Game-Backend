package com.arenacity.domain.model

import com.arenacity.domain.rules.PullTier
import java.time.Instant
import java.util.UUID

/** One pack opening: what it cost and what fell out, kept for the pity guarantees and the daily free pack. */
data class PackOpen(val id: UUID, val clubId: UUID, val kind: CardKind, val priceCoins: Long, val priceGold: Int, val tiers: List<PullTier>, val athleteIds: List<UUID>, val createdAt: Instant)
