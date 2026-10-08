package com.matchdayclub.engine

import com.matchdayclub.engine.model.*
import com.matchdayclub.engine.sim.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PossessionTest {
    private val strong = TeamStrength(85.0, 85.0, 85.0, 85.0, 1.0)
    private val weak = TeamStrength(50.0, 50.0, 50.0, 50.0, 1.0)
    private val fwds = (1..3).map { Athlete("f$it", "n", Position.FWD, Attributes(80, 20, 60, 60, 60)) }

    @Test
    fun `rng is deterministic`() {
        val a = Rng(42).let { r -> List(5) { r.next() } }
        val b = Rng(42).let { r -> List(5) { r.next() } }
        assertEquals(a, b)
    }

    @Test
    fun `stronger midfield wins more possessions`() {
        val rng = Rng(1)
        val wins = (1..5000).count { Possession.homeWins(strong, weak, rng) }
        assertTrue(wins in 2900..3500, "wins=$wins")
    }

    @Test
    fun `stronger attack creates more chances`() {
        val rng = Rng(2)
        val vsWeak = (1..5000).count { Possession.attack(strong, weak, fwds, FOOTBALL, rng) is PossessionOutcome.Chance }
        val vsStrong = (1..5000).count { Possession.attack(weak, strong, fwds, FOOTBALL, rng) is PossessionOutcome.Chance }
        assertTrue(vsWeak > vsStrong * 2, "vsWeak=$vsWeak vsStrong=$vsStrong")
    }

    @Test
    fun `chances convert at a plausible rate`() {
        val rng = Rng(3)
        val chances = (1..20000).mapNotNull { Possession.attack(weak, weak, fwds, FOOTBALL, rng) as? PossessionOutcome.Chance }
        val rate = chances.count { it.scored }.toDouble() / chances.size
        assertTrue(rate in 0.08..0.22, "rate=$rate")
    }
}
