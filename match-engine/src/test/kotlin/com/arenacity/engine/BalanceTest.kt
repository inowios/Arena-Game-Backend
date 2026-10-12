package com.arenacity.engine

import com.arenacity.engine.balance.Balance
import kotlin.test.Test
import kotlin.test.assertTrue

class BalanceTest {
    @Test
    fun `equal teams produce football-like results`() {
        val s = Balance.run(70, 70, 2000)
        assertTrue(s.meanGoals in 2.4..3.0, "meanGoals=${s.meanGoals}")
        assertTrue(s.drawShare in 0.22..0.30, "draws=${s.drawShare}")
        assertTrue(s.homeWinShare in 0.40..0.48, "homeWins=${s.homeWinShare}")
        assertTrue(s.highScoring <= 0.02, "7+ goals share=${s.highScoring}")
    }

    @Test
    fun `20 rating points is a clear but not absolute edge`() {
        val s = Balance.run(80, 60, 2000)
        assertTrue(s.homeWinShare in 0.70..0.90, "homeWins=${s.homeWinShare}")
        assertTrue(s.awayWinShare >= 0.03, "awayWins=${s.awayWinShare}")
    }
}
