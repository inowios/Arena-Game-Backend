package com.arenacity.domain.rules

import com.arenacity.domain.model.Club
import java.time.Instant

/** The club's energy now: [current] of [max]; [nextAt] = the next +1 (null when full), [fullAt] = full again (null when full). */
data class EnergyState(val current: Int, val max: Int, val nextAt: Instant?, val fullAt: Instant?, val regenSeconds: Long, val refillGold: Int)

/**
 * Energy (the owner, 14 October 2026): at most [MAX], +1 every [REGEN_SECONDS]. Nothing ticks on the server: the club
 * keeps the amount at a moment ([Club.energy] at [Club.energyAt]) and the amount now is worked out from it. A card match
 * «Три зони» costs [CARD_MATCH], a friendly [FRIENDLY], league matchday matches nothing; [REFILL_GOLD] gems fill it up.
 */
object Energy {
    const val MAX = 30
    const val REGEN_SECONDS = 360L
    const val CARD_MATCH = 5
    const val FRIENDLY = 3
    const val REFILL_GOLD = 40

    fun state(club: Club, now: Instant): EnergyState {
        val stored = club.energy.coerceAtLeast(0)
        val at = club.energyAt
        if (at == null || stored >= MAX) return EnergyState(MAX, MAX, null, null, REGEN_SECONDS, REFILL_GOLD)
        val gained = (maxOf(0L, now.epochSecond - at.epochSecond) / REGEN_SECONDS).toInt()
        val current = minOf(MAX, stored + gained)
        if (current >= MAX) return EnergyState(MAX, MAX, null, null, REGEN_SECONDS, REFILL_GOLD)
        val nextAt = at.plusSeconds((gained + 1) * REGEN_SECONDS)
        return EnergyState(current, MAX, nextAt, nextAt.plusSeconds((MAX - current - 1) * REGEN_SECONDS), REGEN_SECONDS, REFILL_GOLD)
    }

    /** The club with [cost] spent now (the partial minute towards the next +1 is kept); null when it has too little. */
    fun spend(club: Club, cost: Int, now: Instant): Club? {
        val s = state(club, now)
        if (s.current < cost) return null
        // full: the refill clock starts now; otherwise the time already counted towards the next +1 stays
        val at = if (s.nextAt == null) now else s.nextAt.minusSeconds(REGEN_SECONDS)
        return club.copy(energy = s.current - cost, energyAt = at)
    }

    /** The club with its energy full again. */
    fun fill(club: Club, now: Instant): Club = club.copy(energy = MAX, energyAt = now)
}
