package com.arenacity.domain.rules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClubNamesTest {
    @Test fun `a city name is normalized`() {
        assertEquals("Kyiv Harbour", ClubNames.normalize("  kyiv   Harbour "))
        assertEquals("Гавань київ", ClubNames.normalize("гавань київ"))   // only the first letter is raised
        assertEquals("D'Arc", ClubNames.normalize("d’Arc"))
    }

    @Test fun `letters of any alphabet, digits, spaces, apostrophes and hyphens pass`() {
        listOf("Nova Arena", "Гавань Київ", "Sant'Elia-2", "Ōsaka 7", "Ab").forEach { assertNull(ClubNames.problem(it), it) }
    }

    @Test fun `usernames, symbols and lengths are refused`() {
        listOf("A", "city_01", "neo@arena", "2026", "---", "A".repeat(25)).forEach { assertNotNull(ClubNames.problem(it), it) }
    }
}
