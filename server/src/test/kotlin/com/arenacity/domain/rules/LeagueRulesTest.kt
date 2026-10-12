package com.arenacity.domain.rules

import com.arenacity.domain.model.Fixture
import com.arenacity.domain.model.FixtureKind
import com.arenacity.domain.model.FixtureStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*

class LeagueRulesTest {
    private val ids = List(8) { UUID(0, it.toLong() + 1) }

    @Test
    fun `28 rounds, every club plays everyone 4 times with 14 home games`() {
        val rounds = Schedule.rounds(ids, 7)
        assertEquals(28, rounds.size); assertTrue(rounds.all { it.size == 4 })
        for (c in ids) {
            val games = rounds.flatten().filter { it.first == c || it.second == c }
            assertEquals(28, games.size); assertEquals(14, games.count { it.first == c })
            val opponents = games.map { if (it.first == c) it.second else it.first }
            assertEquals(ids.size - 1, opponents.toSet().size); assertTrue(opponents.groupingBy { it }.eachCount().values.all { it == 4 })
        }
        assertTrue(rounds.all { r -> r.flatMap { listOf(it.first, it.second) }.toSet().size == 8 })
        assertEquals(rounds, Schedule.rounds(ids, 7)); assertNotEquals(rounds, Schedule.rounds(ids, 8))
    }

    @Test
    fun `round instants follow the league timezone`() {
        val i = Schedule.roundInstant(LocalDate.of(2026, 10, 10), 1, "Europe/Kyiv", 20)
        assertEquals(Instant.parse("2026-10-10T17:00:00Z"), i)
        assertEquals(Instant.parse("2026-10-12T17:00:00Z"), Schedule.roundInstant(LocalDate.of(2026, 10, 10), 3, "Europe/Kyiv", 20))
    }

    @Test
    fun `tz bucket and first season start`() {
        val now = Instant.parse("2026-10-09T10:00:00Z")   // 13:00 Kyiv
        assertEquals(3, LeagueRules.tzBucket("Europe/Kyiv", now)); assertEquals(0, LeagueRules.tzBucket("UTC", now)); assertEquals(-4, LeagueRules.tzBucket("America/New_York", now))
        assertEquals(LocalDate.of(2026, 10, 9), LeagueRules.firstSeasonStart("Europe/Kyiv", now))
        assertEquals(LocalDate.of(2026, 10, 10), LeagueRules.firstSeasonStart("Europe/Kyiv", Instant.parse("2026-10-09T16:30:00Z")))   // 19:30 Kyiv
        assertEquals(6, LeagueRules.code(1).length); assertNotEquals(LeagueRules.code(1), LeagueRules.code(2))
    }

    @Test
    fun `standings sort by points gd gf`() {
        val (a, b, c) = ids
        fun f(h: UUID, aw: UUID, hs: Int, `as`: Int) = Fixture(UUID.randomUUID(), null, FixtureKind.LEAGUE, 1, 1, Instant.EPOCH, h, aw, FixtureStatus.PLAYED, hs, `as`, 0, null, Instant.EPOCH, Instant.EPOCH)
        val t = Standings.table(listOf(f(a, b, 2, 0), f(b, c, 1, 1), f(c, a, 3, 1), f(a, c, 0, 0)), listOf(a, b, c))
        assertEquals(listOf(c, a, b), t.map { it.clubId })
        assertEquals(5, t[0].points); assertEquals(4, t[1].points); assertEquals(3, t[0].played)
        assertEquals(Outcome.WIN, Standings.outcome(2, 1)); assertEquals(12, Standings.reputationDelta(Outcome.WIN))
    }
}
