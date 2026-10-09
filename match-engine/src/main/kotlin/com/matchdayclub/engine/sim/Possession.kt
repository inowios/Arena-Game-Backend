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
    /** [points] is what a made shot is worth (football 1; basketball 2 or 3). */
    data class Chance(val shooterId: String, val scored: Boolean, val saved: Boolean, val points: Int = 1) : PossessionOutcome
}

object Possession {
    /** Football defaults; each sport carries its own exponents in [SportProfile.chanceExp] / [SportProfile.conversionExp]. */
    const val CHANCE_EXP = 1.0
    const val CONVERSION_EXP = 0.6

    private fun ratio(a: Double, d: Double, exp: Double, lo: Double, hi: Double) = (a / d).pow(exp).coerceIn(lo, hi)

    fun homeWins(home: TeamStrength, away: TeamStrength, rng: Rng): Boolean =
        rng.next() < home.midfield / (home.midfield + away.midfield)

    fun attack(att: TeamStrength, def: TeamStrength, attackers: List<Athlete>, sport: SportProfile, rng: Rng): PossessionOutcome {
        val pc = (sport.baseChanceRate * ratio(att.attack, def.defense, sport.chanceExp, 0.35, 2.8)).coerceIn(0.0, 0.95)
        if (rng.next() >= pc) return PossessionOutcome.Nothing
        val pool = attackers.filter { !it.injured && (sport.shooterWeight[it.position] ?: 0.0) > 0.0 }
        if (pool.isEmpty()) return PossessionOutcome.Nothing
        val shooter = rng.weighted(pool) { a -> (sport.shooterWeight[a.position] ?: 0.0) * a.attributes.attack }
        // long-range attempt (basketball 3-pointer): drawn only when the sport has one, so football keeps its RNG sequence
        val bonus = sport.bonusShotChance > 0.0 && rng.next() < sport.bonusShotChance
        val conversion = sport.baseConversion * (if (bonus) sport.bonusConversion else 1.0)
        val pg = (conversion * ratio(shooter.effective, def.keeper, sport.conversionExp, 0.5, 2.0)).coerceIn(0.0, 0.9)
        val scored = rng.next() < pg
        val saved = !scored && rng.next() < sport.saveShare
        return PossessionOutcome.Chance(shooter.id, scored, saved, if (bonus) sport.bonusValue else sport.scoreValue)
    }
}
