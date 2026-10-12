package com.arenacity.engine.sim

import com.arenacity.engine.model.*

data class TeamStrength(val attack: Double, val defense: Double, val midfield: Double, val keeper: Double, val tempoCost: Double)

object Strength {
    /**
     * Each component (attack, defense, midfield, keeper) is a weighted mean of role means, weights from the sport profile.
     * Football weights reproduce the original formula: attack = FWD .6 + MID .4, defense = DEF .6 + MID .2 + GK .2, midfield = MID, keeper = GK.
     */
    fun of(lineup: Lineup, sport: SportProfile, isHome: Boolean): TeamStrength {
        val fit = lineup.starters.filter { !it.injured }.take(sport.startersCount)
        val coverage = fit.size.toDouble() / sport.startersCount
        val roleMean = sport.roles.keys.associateWith { p -> fit.filter { it.position == p }.map { it.effective }.let { if (it.isEmpty()) sport.fallbackRating[p] ?: 35.0 else it.average() } }
        fun component(weight: (RoleWeights) -> Double): Double {
            var sum = 0.0; var total = 0.0
            for ((p, w) in sport.roles) { val x = weight(w); if (x <= 0.0) continue; sum += x * roleMean.getValue(p); total += x }
            return if (total == 0.0) 35.0 else sum / total
        }
        var attack = component { it.attack }
        var defense = component { it.defense }
        var midfield = component { it.midfield }
        val keeper = component { it.keeper }
        val t = lineup.tactics
        when (t.style) {
            Style.ATTACK -> { attack *= 1.08; defense *= 0.92 }
            Style.DEFEND -> { attack *= 0.92; defense *= 1.08 }
            Style.BALANCED -> Unit
        }
        when (t.plan) {
            Plan.PRESSING -> midfield *= 1.05
            Plan.COUNTER -> if (t.style == Style.DEFEND) attack *= 1.06
            Plan.POSSESSION -> midfield *= 1.06
            Plan.LONG_BALL -> { attack *= 1.04; midfield *= 0.97 }
            Plan.NONE -> Unit
        }
        val tempoMul = if (t.tempo == Tempo.HIGH) 1.04 else 1.0
        val tempoCost = (if (t.tempo == Tempo.HIGH) 1.5 else 1.0) * (if (t.plan == Plan.PRESSING) 1.2 else 1.0)
        val homeMul = if (isHome) 1.0 + sport.homeAdvantage else 1.0
        return TeamStrength(
            attack = attack * tempoMul * homeMul * coverage,
            defense = defense * tempoMul * coverage,
            midfield = midfield * tempoMul * homeMul * coverage,
            keeper = keeper * coverage,
            tempoCost = tempoCost,
        )
    }
}
