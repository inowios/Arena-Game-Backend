package com.matchdayclub.domain.rules

import com.matchdayclub.domain.model.Rarity
import com.matchdayclub.domain.model.Sport
import com.matchdayclub.engine.model.Position
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class AthleteRulesTest {
    private val now = Instant.parse("2026-10-09T12:00:00Z")
    private val club = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `factory is deterministic and role biased`() {
        val a = AthleteFactory.create(42, club, Sport.FOOTBALL, 60, 60, Position.FWD, now)
        val b = AthleteFactory.create(42, club, Sport.FOOTBALL, 60, 60, Position.FWD, now)
        assertEquals(a, b)
        assertTrue(a.attack > a.defense, "fwd attack ${a.attack} > def ${a.defense}")
        val gk = AthleteFactory.create(7, club, Sport.FOOTBALL, 60, 60, Position.GK, now)
        assertTrue(gk.defense > gk.attack)
        assertTrue(a.age in 17..21)
    }

    @Test
    fun `rarity thresholds`() {
        assertEquals(Rarity.ELITE, AthleteFactory.create(1, club, Sport.FOOTBALL, 70, 70, null, now).rarity)
        assertEquals(Rarity.RARE, AthleteFactory.create(1, club, Sport.FOOTBALL, 58, 58, null, now).rarity)
        assertEquals(Rarity.COMMON, AthleteFactory.create(1, club, Sport.FOOTBALL, 50, 50, null, now).rarity)
    }

    @Test
    fun `starter squad shape`() {
        val s = AthleteFactory.starterSquad(club, now)
        assertEquals(14, s.size); assertEquals(2, s.count { it.position == Position.GK }); assertEquals(5, s.count { it.position == Position.MID })
        assertEquals(14, s.map { it.id }.toSet().size)
        assertEquals(s, AthleteFactory.starterSquad(club, now))
        assertTrue(s.all { it.overall in 30..65 }, s.map { it.overall }.toString())
    }

    @Test
    fun `training rules read the catalog`() {
        assertEquals(1, TrainingRules.slots(1)); assertEquals(3, TrainingRules.slots(3)); assertEquals(2, TrainingRules.delta(2))
        assertEquals(60, TrainingRules.attributeCap(1)); assertEquals(40..55, TrainingRules.academyQuality(1)); assertEquals(4 * 3600L, TrainingRules.medicalCooldownSeconds(1))
    }

    @Test
    fun `fatigue recovers passively`() {
        val a = AthleteFactory.create(3, club, Sport.FOOTBALL, 50, 50, null, now).copy(fatigue = 40, fatigueAt = now)
        assertEquals(40, a.currentFatigue(now)); assertEquals(36, a.currentFatigue(now.plusSeconds(3600))); assertEquals(0, a.currentFatigue(now.plusSeconds(20 * 3600)))
    }
}
