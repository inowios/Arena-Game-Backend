package com.arenacity.arena

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where the copies of the mobile repo's data live in this module (tools/arena/sync_skills.sh). */
object ArenaTestData {
    /** The case files from the classpath (a directory when gradle runs the tests), sorted by name. */
    fun cases(): List<File> {
        val url = ArenaTestData::class.java.getResource("/arena/cases") ?: error("arena/cases missing from the test resources: run tools/arena/sync_skills.sh")
        val dir = File(url.toURI())
        return dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
    }
    /** The mobile checkout, when it is beside this repo (or at ARENA_MOBILE_DIR): the staleness test compares against it. */
    fun mobileDir(): File? {
        val env = System.getenv("ARENA_MOBILE_DIR")?.takeIf { it.isNotBlank() }?.let { File(it) }
        val candidates = listOfNotNull(env, File("../../Arena-Game-Mobile"), File("../Arena-Game-Mobile"))
        return candidates.firstOrNull { File(it, "unity/Assets/Resources/Data/arena_skills.json").isFile }
    }
}

/**
 * The proof of a faithful port: every case file of the client (145 on 20 October 2026) passes on the Kotlin engine
 * exactly as it does on the C# one. A failing case prints the engine's log for it.
 */
class ArenaCasesTest {
    @Test
    fun `every case of the client passes`() {
        val lib = ArenaSkills.library
        val files = ArenaTestData.cases()
        assertTrue(files.size >= 145, "expected the 145 cases, found ${files.size}")
        val failures = ArrayList<String>()
        var passed = 0
        for (f in files) {
            val o = try { CaseRunner.run(f.readText(), lib) } catch (e: Exception) { CaseRunner.Outcome(false, "FAIL: exception $e") }
            if (o.ok) passed++ else failures.add("[${f.name}] ${o.log}")
        }
        assertEquals(files.size, passed, "cases failed:\n" + failures.joinToString("\n"))
    }
}
