package com.matchdayclub.domain.usecase

import com.matchdayclub.domain.model.FixtureStatus
import com.matchdayclub.domain.model.LeagueKind
import com.matchdayclub.domain.usecase.athlete.StarterSquadInitializer
import com.matchdayclub.domain.usecase.club.CreateClubUseCase
import com.matchdayclub.domain.usecase.league.*
import com.matchdayclub.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class MatchdayRunnerTest {
    private val clock = FixedClock(Instant.parse("2026-10-09T10:00:00Z"))   // 13:00 Kyiv
    private val users = FakeUserRepository(); private val clubs = FakeClubRepository(); private val buildings = FakeBuildingRepository()
    private val athletes = FakeAthleteRepository(); private val lineups = FakeLineupRepository(); private val leagues = FakeLeagueRepository(); private val fixtures = FakeFixtureRepository()
    private val bots = BotService(users, clubs, athletes, clock)
    private val leagueService = LeagueService(leagues, fixtures, bots, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock), JoinPublicLeagueInitializer(clubs, leagueService, clock)))
    private val runner = MatchdayRunner(leagues, fixtures, clubs, athletes, LineupService(athletes, lineups), leagueService, clock)
    private val user = UUID.randomUUID()

    @Test
    fun `club joins a public league in its timezone bucket and the first round plays at 20 local`() = runBlocking {
        val club = create(user, "Lions", "#AA0000", "#FFFFFF", "Europe/Kyiv")
        val league = leagues.listForClub(club.id).single()
        assertEquals(LeagueKind.PUBLIC, league.kind); assertEquals(3, league.tzBucket); assertEquals("2026-10-09", league.seasonStart.toString())
        assertEquals(0, runner.runDueRounds(Instant.parse("2026-10-09T16:59:00Z")))
        assertTrue(fixtures.rows.isEmpty())
        val played = runner.runDueRounds(Instant.parse("2026-10-09T17:00:00Z"))
        assertEquals(4, played); assertEquals(8, leagues.members(league.id).size); assertEquals(7, clubs.rows.values.count { it.isBot })
        assertEquals(28 * 4, fixtures.rows.size); assertEquals(1, leagues.rows.getValue(league.id).currentRound)
        val mine = fixtures.rows.values.single { it.involves(club.id) && it.round == 1 }
        assertEquals(FixtureStatus.PLAYED, mine.status); assertNotNull(mine.resultJson)
        val c = clubs.rows.getValue(club.id)
        assertTrue(c.reputation >= 1 && c.coins > 2500, "rewards applied: rep=${c.reputation} coins=${c.coins}")
        assertTrue(athletes.rows.values.filter { it.clubId == club.id }.any { it.fatigue > 0 })
        assertEquals(0, runner.runDueRounds(Instant.parse("2026-10-09T17:30:00Z")))   // idempotent
    }

    @Test
    fun `second club in the same bucket shares the league, different bucket gets its own`() = runBlocking {
        val a = create(UUID.randomUUID(), "Alpha", "#000000", "#FFFFFF", "Europe/Kyiv")
        val b = create(UUID.randomUUID(), "Bravo", "#000000", "#FFFFFF", "Europe/Warsaw")   // UTC+2 → different bucket
        val c = create(UUID.randomUUID(), "Charlie", "#000000", "#FFFFFF", "Europe/Kyiv")
        assertEquals(leagues.listForClub(a.id).single().id, leagues.listForClub(c.id).single().id)
        assertNotEquals(leagues.listForClub(a.id).single().id, leagues.listForClub(b.id).single().id)
    }

    @Test
    fun `season rolls over after 28 rounds`() = runBlocking {
        val club = create(user, "Lions", "#AA0000", "#FFFFFF", "UTC")
        var t = Instant.parse("2026-10-09T20:00:00Z")
        var total = 0
        repeat(28) { total += runner.runDueRounds(t); t = t.plusSeconds(86_400) }
        assertEquals(112, total)
        val league = leagues.listForClub(club.id).single()
        assertEquals(2, league.seasonNumber); assertEquals(0, league.currentRound)   // rolled over right after round 28
        assertEquals("2026-11-06", league.seasonStart.toString())                      // day after the last matchday
        assertEquals(112, fixtures.rows.size)
        assertEquals(4, runner.runDueRounds(t))                                          // next day 20:00 → season 2 round 1
        assertEquals(112 + 112, fixtures.rows.size)
        assertEquals(1, leagues.rows.getValue(league.id).currentRound)
    }
}
