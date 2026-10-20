package com.arenacity.arena

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The engine with the bots on it: deterministic by seed, and the bots never hand the engine an illegal turn. */
class ArenaEngineTest {
    private val lib = ArenaSkills.library
    private val humans = listOf("p001", "p002", "p003", "p004", "p005", "p006", "p007", "p008", "p009", "p010", "p011")

    private fun triple(rng: Rng): List<HeroPick> {
        val ids = humans.toMutableList(); val t = ArrayList<HeroPick>()
        repeat(3) { t.add(HeroPick(ids.removeAt(rng.next(ids.size)), 1)) }
        return t
    }

    private fun playLog(seed: ULong, aiSeedA: ULong, aiSeedB: ULong): Pair<List<String>, MatchState> {
        val rng = Rng(seed)
        val s = Engine.newMatch(lib, triple(rng), triple(rng), rng.next(), null, 0)
        val ev = ArrayList<MatchEvent>()
        Runner.play(s, SmartAi(aiSeedA), SmartAi(aiSeedB), ev)
        return ev.map { it.toString() } to s
    }

    @Test
    fun `the same seed gives the same event log`() {
        val (a, sa) = playLog(2026u, 1u, 2u)
        val (b, sb) = playLog(2026u, 1u, 2u)
        assertEquals(a, b)
        assertEquals(sa.winner, sb.winner); assertEquals(sa.turn, sb.turn)
        assertTrue(sa.finished && sa.winner in 0..1, "the match ends with a winner")
        val (c, _) = playLog(2027u, 1u, 2u)
        assertTrue(a != c, "another seed plays another match")
    }

    @Test
    fun `no illegal AI turns over 500 random matches`() {
        val rng = Rng(77u)
        var illegal = 0; var finished = 0; var turns = 0L
        val t0 = System.nanoTime()
        for (m in 0 until 500) {
            val s = Engine.newMatch(lib, triple(rng), triple(rng), rng.next(), null, m % 2)
            val kinds = listOf("smart", "greedy", "random")
            val a = AiFactory.create(kinds[m % 3], rng.next()); val b = AiFactory.create(kinds[(m / 3) % 3], rng.next())
            val ev = ArrayList<MatchEvent>()
            Runner.play(s, a, b, ev)
            for (e in ev) if (e.kind == "error") { illegal++; if (illegal < 5) println("illegal: ${e.text}") }
            if (s.finished) finished++
            turns += s.turn
        }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        println("ArenaEngineTest: 500 matches in ${"%.0f".format(ms)} ms (${"%.2f".format(ms / 500)} ms/match), ${"%.1f".format(turns / 500.0)} turns/match")
        assertEquals(0, illegal)
        assertEquals(500, finished)
    }
}
