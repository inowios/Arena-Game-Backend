package com.arenacity.domain.rules

import kotlinx.coroutines.runBlocking
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClubNamesTest {
    @Test fun `a username is trimmed and its spaces become underscores`() {
        assertEquals("kai_homura", ClubNames.normalize("  kai   homura "))
        assertEquals("Кай", ClubNames.normalize("Кай"))
    }

    @Test fun `letters of any alphabet, digits, underscores, hyphens and dots pass`() {
        listOf("Kai", "striker_11", "Гравець-7", "Ōsaka.FC", "abc").forEach { assertNull(ClubNames.problem(it), it) }
    }

    @Test fun `spaces, symbols, no letter and lengths are refused`() {
        listOf("Ab", "Kai Homura", "neo@arena", "2026", "___", "A".repeat(17)).forEach { assertNotNull(ClubNames.problem(it), it) }
    }

    @Test fun `a generated name is valid and skips taken ones`() = runBlocking {
        val first = ClubNames.generate({ false }, Random(1))
        assertNull(ClubNames.problem(first), first)
        assertTrue(Regex("[A-Za-z]+_\\d{4}").matches(first), first)
        val second = ClubNames.generate({ it == first }, Random(1))
        assertNotEquals(first, second)
    }
}
