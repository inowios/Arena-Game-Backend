package com.matchdayclub.it

import com.matchdayclub.common.tx.DbTx
import com.matchdayclub.data.repository.*
import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.usecase.account.InboxService
import com.matchdayclub.domain.usecase.athlete.SectionService
import com.matchdayclub.domain.usecase.athlete.StarterSquadInitializer
import com.matchdayclub.domain.usecase.club.ClubService
import com.matchdayclub.domain.usecase.club.CreateClubUseCase
import com.matchdayclub.domain.usecase.cup.CupNotifier
import com.matchdayclub.domain.usecase.cup.CupQueries
import com.matchdayclub.domain.usecase.cup.CupService
import com.matchdayclub.domain.usecase.league.*
import com.matchdayclub.presentation.dto.toDto
import com.matchdayclub.testutil.FixedClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*

/** The weekly cup against PostgreSQL: activity → draw → four rounds → prizes and trophy, all through the real repositories. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class CupIT {
    private val clock = FixedClock(Instant.parse("2026-10-08T10:00:00Z"))   // Thursday
    private val users = UserRepositoryImpl(); private val clubs = ClubRepositoryImpl(); private val buildings = BuildingRepositoryImpl()
    private val athletes = AthleteRepositoryImpl(); private val lineups = LineupRepositoryImpl(); private val leagues = LeagueRepositoryImpl(); private val fixtures = FixtureRepositoryImpl()
    private val seasons = SeasonRepositoryImpl(); private val cups = CupRepositoryImpl(); private val analytics = AnalyticsRepositoryImpl(); private val inbox = InboxRepositoryImpl()
    private val bots = BotService(users, clubs, athletes, clock, leagues)
    private val leagueService = LeagueService(leagues, fixtures, clubs, bots, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock), JoinPublicLeagueInitializer(clubs, leagueService, clock)), tx = DbTx)
    private val runner = MatchdayRunner(leagues, fixtures, clubs, athletes, LineupService(athletes, lineups), leagueService, clock, tx = DbTx, sections = SectionService(buildings, athletes))
    private val service = CupService(cups, clubs, fixtures, bots, runner, seasons, clock, tx = DbTx, notifier = CupNotifier(null, InboxService(inbox, clock)))
    private val queries = CupQueries(ClubService(clubs, buildings, clock), cups, fixtures, clubs, clock)

    @BeforeEach
    fun setUp() { TestDb.fresh() }

    private suspend fun human(name: String, active: Boolean = true): Club {
        val id = UUID.randomUUID()
        users.create(User(id, AuthProvider.GUEST, "cup-$id", null, null, null, true, clock.now(), clock.now()))
        val c = create(id, name, "#112233", "#FFFFFF", "UTC")
        if (active) analytics.markActive(id, LocalDate.parse("2026-10-07"))
        return c
    }

    @Test
    fun `weekly cup from draw to final`() = runBlocking<Unit> {
        val a = human("Alpha FC"); val b = human("Bravo FC"); val c = human("Charlie FC"); human("Idle FC", active = false)
        assertEquals(setOf(a.id, b.id, c.id), cups.activeHumanClubs(LocalDate.parse("2026-10-02")).map { it.first }.toSet())
        assertTrue(cups.activeHumanClubs(LocalDate.parse("2026-10-08")).isEmpty())

        clock.instant = Instant.parse("2026-10-08T12:00:00Z")
        assertEquals(0, service.tick(clock.now()))
        service.tick(clock.now())
        val week = LocalDate.parse("2026-10-05")
        val cup = cups.cupsOf(0, week).single()
        assertEquals(16, cups.entries(cup.id).size); assertEquals(16, cups.weekEntrants(week).size)
        assertEquals(8, cups.ties(cup.id).size)
        assertEquals(8, fixtures.findByIds(cups.ties(cup.id).map { it.fixtureId }).count { it.kind == FixtureKind.CUP && it.status == FixtureStatus.SCHEDULED })
        assertEquals(3, inbox.list(a.id, 50).size + inbox.list(b.id, 50).size + inbox.list(c.id, 50).size)

        clock.instant = Instant.parse("2026-10-11T18:30:00Z")
        assertEquals(15, service.tick(clock.now()))
        val done = cups.find(cup.id)!!
        assertEquals(CupStatus.FINISHED, done.status); assertNotNull(done.winnerClubId)
        val out = cups.entries(cup.id).groupingBy { it.outRound }.eachCount()
        assertEquals(mapOf(1 to 8, 2 to 4, 3 to 2, 4 to 1, null to 1), out)
        assertTrue(cups.ties(cup.id).all { it.winnerClubId != null }); assertEquals(15, cups.ties(cup.id).size)
        val champion = clubs.findById(done.winnerClubId!!)!!
        assertEquals(if (champion.isBot) 0 else 1, seasons.trophiesOf(champion.id).count { it.kind == TrophyKind.CUP })
        val view = queries.current(a.userId).toDto()
        assertEquals("FINISHED", view.state); assertEquals(15, view.ties.size)
        assertTrue(Json.encodeToString(view).contains("\"rounds\":4"))
        assertEquals(cups.entry(week, a.id), cups.lastEntryBefore(a.id, week.plusDays(7)))
        assertEquals(0, service.tick(clock.now()))
        // analytics retention runs on the same database
        analytics.record(listOf(AnalyticsEvent(null, "old_event", at = Instant.parse("2025-01-01T00:00:00Z")), AnalyticsEvent(null, "new_event", at = clock.now())))
        assertEquals(1, analytics.purgeEventsBefore(Instant.parse("2025-06-01T00:00:00Z")))
    }
}
