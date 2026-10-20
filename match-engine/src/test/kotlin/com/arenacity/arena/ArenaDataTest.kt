package com.arenacity.arena

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The copied data: readable, valid, and the same as the mobile repo's when that checkout is present. */
class ArenaDataTest {
    @Test
    fun `the library loads and validates`() {
        val lib = ArenaSkills.library
        assertEquals(11, lib.heroes.size)
        assertEquals(emptyList(), Validator.check(lib))
        assertTrue(lib.skillCount >= 44, "skills: ${lib.skillCount}")
        for (h in lib.heroes) assertTrue(h.kit().all { it != null }, "${h.id}: a four-skill kit")
    }

    @Test
    fun `the copies are not stale against the mobile checkout`() {
        val mobile = ArenaTestData.mobileDir()
        if (mobile == null) { println("ArenaDataTest: no mobile checkout beside this repo (ARENA_MOBILE_DIR), staleness not checked"); return }
        val src = File(mobile, "unity/Assets/Resources/Data/arena_skills.json").readText()
        assertEquals(src, ArenaSkills.text(), "match-engine/src/main/resources/arena/arena_skills.json differs from the mobile file: run tools/arena/sync_skills.sh")
        val srcCases = File(mobile, "unity/Tests/ArenaCases").listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        val copies = ArenaTestData.cases()
        assertEquals(srcCases.map { it.name }, copies.map { it.name }, "the case files differ from the mobile ones: run tools/arena/sync_skills.sh")
        for ((a, b) in srcCases.zip(copies)) assertEquals(a.readText(), b.readText(), "${a.name} differs from the mobile file: run tools/arena/sync_skills.sh")
    }
}
