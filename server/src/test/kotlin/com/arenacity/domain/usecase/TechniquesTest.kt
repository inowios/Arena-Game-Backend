package com.arenacity.domain.usecase

import com.arenacity.domain.model.Sport
import com.arenacity.domain.rules.AthleteFactory
import com.arenacity.domain.rules.Techniques
import com.arenacity.engine.model.Position
import com.arenacity.testutil.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class TechniquesTest {
    private val clock = FixedClock(Instant.parse("2026-10-09T10:00:00Z"))

    @Test
    fun `generated athletes know a few moves of their role, the better the more`() {
        val now = clock.now(); val club = UUID.randomUUID()
        val star = AthleteFactory.create(11L, club, Sport.FOOTBALL, 78, 78, Position.FWD, now)
        assertEquals(3, star.techniques.size, "star ${star.techniques}")
        assertTrue(star.techniques.values.all { it in 1..3 })
        assertTrue(star.techniques.keys.all { it in listOf("SHOOT", "FEINT", "CROSS", "PASS", "JUMP") })
        val rookie = AthleteFactory.create(12L, club, Sport.FOOTBALL, 44, 44, Position.DEF, now)
        assertTrue(rookie.techniques.size <= 1 && rookie.techniques.values.all { it == 1 }, "rookie ${rookie.techniques}")
        val keeper = AthleteFactory.create(13L, club, Sport.FOOTBALL, 80, 80, Position.GK, now)
        assertTrue(keeper.techniques.isEmpty())
        // the seed decides: the same athlete always comes with the same moves
        assertEquals(star.techniques, AthleteFactory.create(11L, club, Sport.FOOTBALL, 78, 78, Position.FWD, now).techniques)
        // a talent among the role's moves, none for the keeper; its steps cost a point less (never below one)
        assertNotNull(star.talent); assertTrue(star.talent in listOf("SHOOT", "FEINT", "CROSS", "PASS", "JUMP")); assertNull(keeper.talent)
        assertEquals(1, Techniques.cost(1, talent = true)); assertEquals(1, Techniques.cost(3, talent = true)); assertEquals(3, Techniques.cost(5, talent = true)); assertEquals(4, Techniques.cost(5))
    }
}
