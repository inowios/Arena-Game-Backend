package com.matchdayclub.domain.rules

import com.matchdayclub.domain.model.Athlete
import com.matchdayclub.domain.model.Rarity
import kotlin.math.roundToLong

/**
 * The transfer market with NPC sellers: twelve listings per club per six-hour period, generated from the club, the
 * period and a refresh counter (no rows until something is bought), quality by arena; buying costs coins, selling
 * one of your own returns half his value.
 */
object Market {
    const val PERIOD_SECONDS = 6 * 3600L
    const val LISTINGS = 12
    const val REFRESH_GOLD = 5

    fun period(epochSecond: Long) = epochSecond / PERIOD_SECONDS
    fun periodEnd(epochSecond: Long) = (period(epochSecond) + 1) * PERIOD_SECONDS

    /** Quality range of listings in an arena; a few listings each period are a cut above. */
    fun quality(arena: Arena, index: Int): IntRange {
        val lo = 44 + arena.index * 5 + if (index % 4 == 3) 8 else 0
        return lo..(lo + 12)
    }

    fun seed(clubId: java.util.UUID, period: Long, salt: Int, index: Int): Long =
        (clubId.mostSignificantBits xor (period * 1_000_003L) xor (salt * 7_919L) xor (index * 104_729L)) and Long.MAX_VALUE

    /** What a player costs: rating squared, rarity, youth with room to grow, age. */
    fun price(a: Athlete): Long {
        val base = a.overall.toDouble() * a.overall * 1.2
        val rarity = when (a.rarity) { Rarity.ELITE -> 1.6; Rarity.RARE -> 1.25; Rarity.COMMON -> 1.0 }
        val youth = if (a.age <= 22 && a.potential - a.attributeAverage >= 8) 1.3 else 1.0
        val age = when { a.age >= 33 -> 0.5; a.age >= 30 -> 0.7; else -> 1.0 }
        return (base * rarity * youth * age / 10).roundToLong() * 10
    }

    fun sellPrice(a: Athlete): Long = price(a) / 2
}
