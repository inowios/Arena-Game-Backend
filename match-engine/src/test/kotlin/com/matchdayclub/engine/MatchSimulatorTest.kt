package com.matchdayclub.engine

import com.matchdayclub.engine.model.*
import com.matchdayclub.engine.sim.MatchSimulator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MatchSimulatorTest {
    private fun squad(v: Int, team: String, bench: Int = 3) = Lineup(team,
        starters = listOf(Position.GK to 1, Position.DEF to 4, Position.MID to 4, Position.FWD to 2).flatMap { (p, n) -> (1..n).map { Athlete("$team-$p-$it", "n", p, Attributes(v, v, v, v, v)) } },
        bench = (1..bench).map { Athlete("$team-bench-$it", "n", Position.MID, Attributes(v, v, v, v, v)) })

    @Test
    fun `deterministic for the same seed`() {
        val a = MatchSimulator.simulate(squad(70, "h"), squad(70, "a"), FOOTBALL, 123L)
        val b = MatchSimulator.simulate(squad(70, "h"), squad(70, "a"), FOOTBALL, 123L)
        assertEquals(a, b)
        val c = MatchSimulator.simulate(squad(70, "h"), squad(70, "a"), FOOTBALL, 124L)
        assertTrue(a != c)
    }

    @Test
    fun `events are ordered and bracketed`() {
        val r = MatchSimulator.simulate(squad(70, "h"), squad(70, "a"), FOOTBALL, 7L)
        assertEquals(EventType.KICKOFF, r.events.first().type)
        assertEquals(EventType.FULLTIME, r.events.last().type)
        assertEquals(1, r.events.count { it.type == EventType.HALFTIME })
        assertTrue(r.events.zipWithNext().all { (x, y) -> x.minute <= y.minute })
        assertEquals(r.score.home, r.events.count { it.type == EventType.GOAL && it.team == "h" })
        assertEquals(r.score.away, r.events.count { it.type == EventType.GOAL && it.team == "a" })
    }

    @Test
    fun `much stronger team wins most matches`() {
        val wins = (1..300).count { MatchSimulator.simulate(squad(85, "h"), squad(55, "a"), FOOTBALL, it.toLong()).let { r -> r.score.home > r.score.away } }
        assertTrue(wins > 240, "wins=$wins")
    }

    @Test
    fun `injured starter gets substituted`() {
        val results = (1..400).map { MatchSimulator.simulate(squad(70, "h"), squad(70, "a"), FOOTBALL, it.toLong()) }
        val withInjury = results.filter { r -> r.events.any { it.type == EventType.INJURY } }
        assertTrue(withInjury.isNotEmpty())
        assertTrue(withInjury.all { r -> r.events.any { it.type == EventType.SUBSTITUTION } })
    }
}
