package com.matchdayclub.engine.sim

import com.matchdayclub.engine.model.*

data class TeamStrength(val attack: Double, val defense: Double, val midfield: Double, val keeper: Double, val tempoCost: Double)

object Strength {
    fun of(lineup: Lineup, sport: SportProfile, isHome: Boolean): TeamStrength {
        val fit = lineup.starters.filter { !it.injured }.take(sport.startersCount)
        val coverage = fit.size.toDouble() / sport.startersCount
        fun mean(p: Position, fallback: Double) = fit.filter { it.position == p }.map { it.effective }.let { if (it.isEmpty()) fallback else it.average() }
        val fwd = mean(Position.FWD, 35.0)
        val mid = mean(Position.MID, 35.0)
        val def = mean(Position.DEF, 35.0)
        val gk = fit.filter { it.position == Position.GK }.maxOfOrNull { it.effective } ?: 30.0

        var attack = fwd * 0.6 + mid * 0.4
        var defense = def * 0.6 + mid * 0.2 + gk * 0.2
        var midfield = mid
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
            keeper = gk * coverage,
            tempoCost = tempoCost,
        )
    }
}
