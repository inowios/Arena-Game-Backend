package com.arenacity.domain.rules

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** The day's offer: [spec] for [price] gems instead of [oldPrice] ([discountPercent] off), on sale until [endsAt]. */
data class DailyOffer(val day: LocalDate, val spec: PackSpec, val oldPrice: Int, val price: Int, val discountPercent: Int, val endsAt: Instant)

/**
 * The daily pack offer (the HUD's «−N%» card; the owner, 14 October 2026): one gem pack a UTC day at 30–50 % off, the
 * same all day (a hash of the day, no stored state), on sale until the next UTC midnight, bought once a day per club.
 * The pack is one the club's level has opened (money speeds up, it does not buy a kind the club has not grown into,
 * plan §3b), so the day's pick is taken among those; a club below the first gem pack sees that one, locked.
 */
object DailyOffers {
    val DISCOUNTS = listOf(30, 35, 40, 45, 50)

    private fun mix(x: Long): Long {   // splitmix64's finaliser: a well spread hash of the day
        var z = x + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun dayOf(now: Instant): LocalDate = now.atOffset(ZoneOffset.UTC).toLocalDate()

    /** The packs the offer can be at [clubLevel]: the gem packs the level has opened (at least the first one). */
    fun candidates(clubLevel: Int): List<PackSpec> {
        val gem = Packs.ALL.filter { it.gold > 0 }
        return gem.filter { clubLevel >= it.unlockLevel }.ifEmpty { listOf(gem.first()) }
    }

    fun of(now: Instant, clubLevel: Int): DailyOffer {
        val day = dayOf(now)
        val h = mix(day.toEpochDay())
        val list = candidates(clubLevel)
        val spec = list[Math.floorMod(h, list.size.toLong()).toInt()]
        val discount = DISCOUNTS[Math.floorMod(h ushr 32, DISCOUNTS.size.toLong()).toInt()]
        val price = Math.round(spec.gold * (100 - discount) / 100.0).toInt()
        return DailyOffer(day, spec, spec.gold, price, discount, day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))
    }
}
