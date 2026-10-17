package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.Sport
import com.arenacity.domain.rules.AthleteFactory
import com.arenacity.domain.rules.Growth
import com.arenacity.domain.rules.Techniques
import com.arenacity.domain.usecase.athlete.StarterSquadInitializer
import com.arenacity.domain.usecase.athlete.UpgradeTechniqueUseCase
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.club.CreateClubUseCase
import com.arenacity.engine.model.Position
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.*

class TechniquesTest {
    private val clock = FixedClock(Instant.parse("2026-10-09T10:00:00Z"))
    private val clubs = FakeClubRepository(); private val athletes = FakeAthleteRepository()
    private val service = ClubService(clubs, clock)
    private val create = CreateClubUseCase(clubs, clock, listOf(StarterSquadInitializer(athletes, clock)))
    private val upgrade = UpgradeTechniqueUseCase(service, athletes, clock)
    private val u1 = UUID.randomUUID()

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

    @Test
    fun `experience brings skill points and the tree is climbed step by step`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val fwd = athletes.listByClub(club.id).first { it.position == Position.FWD }.copy(techniques = emptyMap(), skillPoints = 0, xpTotal = 0, talent = null)   // no talent: full step costs
        // 130 xp over a career = one point (every 120)
        val g = Growth.apply(fwd, 130, 99, Random(1))
        assertEquals(1, g.skillPoints); assertEquals(1, g.athlete.skillPoints); assertEquals(130, g.athlete.xpTotal)
        val g2 = Growth.apply(g.athlete, 110, 99, Random(1))
        assertEquals(1, g2.skillPoints); assertEquals(2, g2.athlete.skillPoints)
        athletes.rows[fwd.id] = fwd.copy(skillPoints = 3)
        val r1 = upgrade(u1, fwd.id, "shoot")
        assertEquals(1, r1.level); assertEquals(1, r1.cost); assertEquals(2, r1.athlete.skillPoints); assertEquals(1, r1.athlete.technique("SHOOT"))
        val r2 = upgrade(u1, fwd.id, "SHOOT")
        assertEquals(2, r2.level); assertEquals(1, r2.athlete.skillPoints)
        assertFailsWith<ValidationException> { upgrade(u1, fwd.id, "SHOOT") }   // level 3 costs 2
        assertFailsWith<ValidationException> { upgrade(u1, fwd.id, "DANCE") }
        val gk = athletes.listByClub(club.id).first { it.position == Position.GK }
        athletes.rows[gk.id] = gk.copy(skillPoints = 9)
        assertFailsWith<ValidationException> { upgrade(u1, gk.id, "TACKLE") }
        athletes.rows[fwd.id] = athletes.rows.getValue(fwd.id).copy(techniques = mapOf("FEINT" to Techniques.MAX_LEVEL), skillPoints = 20)
        assertFailsWith<ConflictException> { upgrade(u1, fwd.id, "FEINT") }
        // the whole tree costs 11 points
        assertEquals(11, (1..Techniques.MAX_LEVEL).sumOf { Techniques.cost(it) })
    }
}
