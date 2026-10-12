package com.arenacity.engine

import com.arenacity.engine.balance.Balance
import com.arenacity.engine.model.*
import com.arenacity.engine.sim.MatchSimulator
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiSportTest {
    private fun game(sport: SportProfile, h: Int, a: Int, seed: Long) =
        MatchSimulator.simulate(Balance.squad(h, "h", seed, sport), Balance.squad(a, "a", seed + 50_000, sport), sport, seed)

    @Test
    fun `basketball scores add up, has quarters and never ends level`() {
        repeat(300) { i ->
            val r = game(BASKETBALL, 70, 70, i.toLong())
            assertTrue(r.score.home != r.score.away, "draw in game $i")
            assertEquals(r.score.home, r.events.filter { it.type == EventType.GOAL && it.team == "h" }.sumOf { it.value ?: 0 })
            assertEquals(r.score.away, r.events.filter { it.type == EventType.GOAL && it.team == "a" }.sumOf { it.value ?: 0 })
            assertTrue(r.events.filter { it.type == EventType.GOAL }.all { it.value == 2 || it.value == 3 })
            assertEquals(3, r.events.count { it.type == EventType.PERIOD })
            assertTrue(r.periods.size >= 4)
            assertEquals(r.score, Score(r.periods.sumOf { it.home }, r.periods.sumOf { it.away }), "periods sum to the score")
            assertEquals(r.overtime, r.periods.size > 4)
            assertTrue(r.events.zipWithNext().all { (x, y) -> x.minute <= y.minute })
            assertEquals(EventType.FULLTIME, r.events.last().type)
        }
    }

    @Test
    fun `basketball is deterministic per seed`() {
        assertEquals(game(BASKETBALL, 72, 68, 9L), game(BASKETBALL, 72, 68, 9L))
    }

    @Test
    fun `tennis is best of three with valid sets`() {
        repeat(300) { i ->
            val r = game(TENNIS, 70, 68, i.toLong())
            assertTrue((r.score.home == 2) xor (r.score.away == 2), "one winner with 2 sets: ${r.score}")
            assertEquals(r.score.home + r.score.away, r.periods.size)
            for (s in r.periods) {
                val hi = maxOf(s.home, s.away); val lo = minOf(s.home, s.away)
                assertTrue((hi == 6 && lo <= 4) || (hi == 7 && (lo == 5 || lo == 6)), "invalid set $s")
            }
            assertEquals(r.periods.size, r.events.count { it.type == EventType.SET })
            assertEquals(r.periods.sumOf { it.home + it.away }, r.events.count { it.type == EventType.GAME || it.type == EventType.BREAK })
            assertEquals(EventType.FULLTIME, r.events.last().type)
        }
    }

    @Test
    fun `stronger player usually wins at tennis and basketball`() {
        val tennis = Balance.run(80, 60, 600, TENNIS)
        assertTrue(tennis.homeWinShare in 0.85..0.99, "tennis homeW=${tennis.homeWinShare}")
        val even = Balance.run(70, 70, 1000, TENNIS)
        assertTrue(abs(even.homeWinShare - 0.5) < 0.07, "tennis even homeW=${even.homeWinShare}")
        val bb = Balance.run(75, 70, 1000, BASKETBALL)
        assertTrue(bb.homeWinShare in 0.62..0.85, "basketball +5 homeW=${bb.homeWinShare}")
        val bbEven = Balance.run(70, 70, 1000, BASKETBALL)
        assertTrue(bbEven.homeWinShare in 0.50..0.62 && bbEven.meanGoals in 150.0..200.0, "basketball even: $bbEven")
    }
}
