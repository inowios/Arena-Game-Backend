package com.matchdayclub.domain.rules

import java.time.LocalDate
import java.util.UUID
import kotlin.test.*

class EconomyTest {
    private val d = LocalDate.of(2026, 10, 9)

    @Test
    fun `login calendar advances, loops after 7 and resets on a skipped day`() {
        assertEquals(1, Economy.nextLoginDay(null, 0, d))
        assertEquals(2, Economy.nextLoginDay(d.minusDays(1), 1, d))
        assertEquals(1, Economy.nextLoginDay(d.minusDays(1), 7, d))
        assertEquals(1, Economy.nextLoginDay(d.minusDays(2), 5, d))
        assertEquals(5, Economy.nextLoginDay(d, 5, d))
        assertEquals(Reward(coins = 800, gold = 5), Economy.loginReward(7))
    }

    @Test
    fun `three distinct tasks per day, deterministic, match task only on match days`() {
        val c = UUID.randomUUID()
        val t = Economy.tasksFor(c, d, includeMatch = true)
        assertEquals(3, t.size); assertEquals(3, t.toSet().size); assertEquals(t, Economy.tasksFor(c, d, true))
        assertFalse(Economy.tasksFor(c, d, includeMatch = false).contains(TaskKind.WIN_MATCH))
    }

    @Test
    fun `pass levels and rewards`() {
        assertEquals(0, Economy.passLevel(99)); assertEquals(1, Economy.passLevel(100)); assertEquals(30, Economy.passLevel(99_999))
        assertEquals(Reward(coins = 300), Economy.freeReward(3)); assertEquals(Reward(gold = 4), Economy.freeReward(10))
        assertTrue(Economy.premiumReward(10).gold > Economy.freeReward(10).gold)
        assertEquals(100, Economy.PRODUCTS.getValue("gold_small").gold); assertTrue(Economy.PRODUCTS.getValue("season_pass").premiumPass)
    }
}
