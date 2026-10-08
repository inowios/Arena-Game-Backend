package com.matchdayclub.engine.sim

import com.matchdayclub.engine.model.*
import java.util.SplittableRandom
import kotlin.math.pow

class Rng(seed: Long) {
    private val r = SplittableRandom(seed)
    fun next(): Double = r.nextDouble()
    fun <T> pick(list: List<T>): T = list[r.nextInt(list.size)]
    fun <T> weighted(items: List<T>, weight: (T) -> Double): T {
        val total = items.sumOf(weight)
        var x = next() * total
        for (i in items) { x -= weight(i); if (x <= 0) return i }
        return items.last()
    }
}

sealed interface PossessionOutcome {
    data object Nothing : PossessionOutcome
    data class Chance(val shooterId: String, val scored: Boolean, val saved: Boolean) : PossessionOutcome
}

object Possession {
    private fun ratio(a: Double, d: Double, lo: Double = 0.25, hi: Double = 4.0) = (a / d).pow(1.6).coerceIn(lo, hi)

    fun homeWins(home: TeamStrength, away: TeamStrength, rng: Rng): Boolean =
        rng.next() < home.midfield / (home.midfield + away.midfield)

    fun attack(att: TeamStrength, def: TeamStrength, attackers: List<Athlete>, sport: SportProfile, rng: Rng): PossessionOutcome {
        val pc = (sport.baseChanceRate * ratio(att.attack, def.defense)).coerceIn(0.0, 0.95)
        if (rng.next() >= pc) return PossessionOutcome.Nothing
        val pool = attackers.filter { !it.injured && it.position != Position.GK }
        if (pool.isEmpty()) return PossessionOutcome.Nothing
        val shooter = rng.weighted(pool) { a ->
            val role = when (a.position) { Position.FWD -> 3.0; Position.MID -> 1.0; Position.DEF -> 0.2; Position.GK -> 0.0 }
            role * a.attributes.attack
        }
        val pg = (sport.baseConversion * ratio(shooter.effective, def.keeper, 0.4, 2.5)).coerceIn(0.0, 0.9)
        val scored = rng.next() < pg
        val saved = !scored && rng.next() < 0.55
        return PossessionOutcome.Chance(shooter.id, scored, saved)
    }
}
