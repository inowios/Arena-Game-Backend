package com.arenacity.domain.rules

import com.arenacity.domain.model.CardKind
import kotlin.random.Random

/** The four pull tiers of a pack, with public chances; the kind of the pack decides the frame, the tier the quality. */
enum class PullTier(val quality: IntRange) { COMMON(45..57), RARE(58..69), EPIC(70..81), LEGEND(82..92) }

/** A pack on sale: its card kind, its price (coins for the basic pack, gold for the rest) and its public chances in percent. */
data class PackSpec(val kind: CardKind, val coins: Long, val gold: Int, val chances: List<Double>) {
    val unlockLevel get() = kind.unlockLevel
}

/**
 * Card packs (plan §3b): a pack is a kind plus public chances; three cards an opening. An epic is guaranteed within
 * [EPIC_PITY] cards, a legend within [LEGEND_PITY]; the basic pack is free once a day. Packs open kinds the club level
 * has opened, so money speeds up and offers choice but cannot buy a kind the club has not grown into.
 *
 * DEPRECATED (16 October 2026): heroes replace cards and packs (docs/claude/HEROES-INSTEAD-OF-CARDS.md in the mobile repo;
 * the server side: rules.Heroes, usecase.hero.HeroUseCases). Kept, unchanged: the client dropped packs on 17 October 2026, but an older app version in production may still call them.
 */
object Packs {
    const val CARDS_PER_PACK = 3
    const val EPIC_PITY = 30
    const val LEGEND_PITY = 120
    /** How many cards a club may hold in all; packs stop there (the squad limit of 25 is for the pitch, not the album). */
    const val COLLECTION_MAX = 150

    val ALL = listOf(
        PackSpec(CardKind.CLASSIC, coins = 600, gold = 0, chances = listOf(70.0, 22.0, 6.5, 1.5)),
        PackSpec(CardKind.TOURNAMENT, coins = 0, gold = 60, chances = listOf(58.0, 28.0, 10.0, 4.0)),
        PackSpec(CardKind.PREMIUM, coins = 0, gold = 120, chances = listOf(48.0, 32.0, 14.0, 6.0)),
        PackSpec(CardKind.GOLD, coins = 0, gold = 240, chances = listOf(38.0, 35.0, 19.0, 8.0)),
        PackSpec(CardKind.LEGEND, coins = 0, gold = 480, chances = listOf(28.0, 35.0, 24.0, 13.0)),
    )
    fun of(kind: CardKind): PackSpec? = ALL.firstOrNull { it.kind == kind }

    /** The tier a roll lands on; [sinceEpic] and [sinceLegend] are the cards pulled since the last of each, for the pity. */
    fun roll(spec: PackSpec, r: Random, sinceEpic: Int, sinceLegend: Int): PullTier {
        if (sinceLegend + 1 >= LEGEND_PITY) return PullTier.LEGEND
        if (sinceEpic + 1 >= EPIC_PITY) return PullTier.EPIC
        var x = r.nextDouble() * 100.0
        for ((i, c) in spec.chances.withIndex()) { x -= c; if (x < 0) return PullTier.entries[i] }
        return PullTier.COMMON
    }
}
