package com.matchdayclub.engine

import com.matchdayclub.engine.model.*
import com.matchdayclub.engine.sim.Strength
import kotlin.test.Test
import kotlin.test.assertTrue

class StrengthTest {
    private fun squad(v: Int, team: String = "t") = Lineup(team, Position.values().flatMap { p ->
        val n = when (p) { Position.GK -> 1; Position.DEF -> 4; Position.MID -> 4; Position.FWD -> 2 }
        (1..n).map { Athlete("$team-$p-$it", "n", p, Attributes(v, v, v, v, v)) }
    })

    @Test
    fun `better players give higher strength`() {
        val weak = Strength.of(squad(50), FOOTBALL, false)
        val strong = Strength.of(squad(80), FOOTBALL, false)
        assertTrue(strong.attack > weak.attack && strong.defense > weak.defense && strong.keeper > weak.keeper)
    }

    @Test
    fun `attacking style trades defense for attack`() {
        val base = Strength.of(squad(70), FOOTBALL, false)
        val att = Strength.of(squad(70).copy(tactics = Tactics(style = Style.ATTACK)), FOOTBALL, false)
        assertTrue(att.attack > base.attack && att.defense < base.defense)
    }

    @Test
    fun `home advantage applies`() {
        val away = Strength.of(squad(70), FOOTBALL, false)
        val home = Strength.of(squad(70), FOOTBALL, true)
        assertTrue(home.attack > away.attack)
    }

    @Test
    fun `missing players weaken the team`() {
        val full = Strength.of(squad(70), FOOTBALL, false)
        val short = Strength.of(squad(70).let { it.copy(starters = it.starters.drop(3)) }, FOOTBALL, false)
        assertTrue(short.attack < full.attack * 0.8)
    }
}
