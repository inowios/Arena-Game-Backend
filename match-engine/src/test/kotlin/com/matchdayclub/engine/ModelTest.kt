package com.matchdayclub.engine

import com.matchdayclub.engine.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelTest {
    private fun a(pos: Position, v: Int) = Athlete("id-$pos-$v", "n", pos, Attributes(v, v, v, v, v))

    @Test
    fun `overall weights position`() {
        val gk = Athlete("g", "g", Position.GK, Attributes(attack = 10, defense = 80, physical = 60, technique = 50, mental = 70))
        val fw = Athlete("f", "f", Position.FWD, Attributes(attack = 80, defense = 10, physical = 60, technique = 50, mental = 70))
        assertTrue(gk.overall > 55 && fw.overall > 55, "specialists are rated by their role: gk=${gk.overall} fw=${fw.overall}")
        assertEquals(70, a(Position.MID, 70).overall)
    }

    @Test
    fun `result serializes to json and back`() {
        val r = MatchResult("football", 1L, "h", "a", Score(2, 1), listOf(MatchEvent(12, EventType.GOAL, "h", "x")), 55, 10, 7)
        val json = Json.encodeToString(r)
        assertEquals(r, Json.decodeFromString<MatchResult>(json))
    }

    @Test
    fun `football profile`() {
        assertEquals(11, FOOTBALL.startersCount)
        assertEquals(90, FOOTBALL.durationMinutes)
    }
}
