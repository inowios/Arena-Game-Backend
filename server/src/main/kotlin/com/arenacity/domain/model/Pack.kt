package com.arenacity.domain.model

import com.arenacity.domain.rules.PullTier
import java.time.Instant
import java.util.UUID

/**
 * One pack opening: what it cost and what fell out, kept for the pity guarantees and the daily free pack.
 *
 * DEPRECATED (16 October 2026): heroes replace cards and packs (docs/claude/HEROES-INSTEAD-OF-CARDS.md in the mobile repo;
 * the server side: rules.Heroes, usecase.hero.HeroUseCases). Kept, unchanged, while the current client still opens packs.
 */
data class PackOpen(val id: UUID, val clubId: UUID, val kind: CardKind, val priceCoins: Long, val priceGold: Int, val tiers: List<PullTier>, val athleteIds: List<UUID>, val createdAt: Instant)
