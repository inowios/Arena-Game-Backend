package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.model.FixtureKind
import com.arenacity.domain.model.FixtureStatus
import com.arenacity.domain.usecase.athlete.StarterSquadInitializer
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.club.CreateClubUseCase
import com.arenacity.domain.usecase.league.*
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class LeagueUseCasesTest {
    private val clock = FixedClock(Instant.parse("2026-10-09T10:00:00Z"))
    private val users = FakeUserRepository(); private val clubs = FakeClubRepository(); private val buildings = FakeBuildingRepository()
    private val athletes = FakeAthleteRepository(); private val lineups = FakeLineupRepository(); private val leagues = FakeLeagueRepository(); private val fixtures = FakeFixtureRepository()
    private val bots = BotService(users, clubs, athletes, clock)
    private val leagueService = LeagueService(leagues, fixtures, clubs, bots, clock)
    private val clubService = ClubService(clubs, buildings, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock), JoinPublicLeagueInitializer(clubs, leagueService, clock)))
    private val runner = MatchdayRunner(leagues, fixtures, clubs, athletes, LineupService(athletes, lineups), leagueService, clock)
    private val queries = LeagueQueries(leagues, fixtures, clubs, athletes, clock)
    private val createPrivate = CreatePrivateLeagueUseCase(clubService, leagues, clock)
    private val join = JoinPrivateLeagueUseCase(clubService, leagues)
    private val friendly = PlayFriendlyUseCase(clubService, clubs, fixtures, bots, runner, queries, clock)
    private val u1 = UUID.randomUUID(); private val u2 = UUID.randomUUID()

    @Test
    fun `private league aggregates members public results`() = runBlocking<Unit> {
        val a = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val b = create(u2, "Tigers", "#00AA00", "#FFFFFF", "UTC")
        val priv = createPrivate(u1, "Office league")
        assertEquals(6, priv.code!!.length)
        join(u2, priv.code!!.lowercase())
        assertFailsWith<ConflictException> { join(u2, priv.code!!) }
        runner.runDueRounds(Instant.parse("2026-10-09T20:00:00Z"))
        val view = queries.view(priv.id)
        assertEquals(2, view.table.size); assertTrue(view.table.all { it.played == 1 })
        val (pub, privs) = queries.mine(a.id)
        assertNotNull(pub); assertEquals(8, pub.table.size); assertEquals(1, privs.size)
        assertEquals(b.id, queries.view(priv.id).clubs.getValue(b.id).id)
    }

    @Test
    fun `a friendly costs energy and is refused with too little`() = runBlocking<Unit> {
        val a = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        friendly(u1, null)
        val after = clubs.rows.getValue(a.id)
        assertEquals(com.arenacity.domain.rules.Energy.MAX - com.arenacity.domain.rules.Energy.FRIENDLY, com.arenacity.domain.rules.Energy.state(after, clock.now()).current)
        clubs.update(after.copy(energy = 2, energyAt = clock.now()))
        val e = assertFailsWith<com.arenacity.domain.exception.ValidationException> { friendly(u1, null) }
        assertEquals("Not enough energy", e.template)
        assertEquals(1, clubs.rows.getValue(a.id).friendliesPlayed)   // nothing played, nothing counted
        clock.advanceSeconds(com.arenacity.domain.rules.Energy.REGEN_SECONDS)
        friendly(u1, null)
        assertEquals(0, com.arenacity.domain.rules.Energy.state(clubs.rows.getValue(a.id), clock.now()).current)
    }

    @Test
    fun `friendlies are capped per day and reward coins without reputation`() = runBlocking<Unit> {
        val a = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val before = clubs.rows.getValue(a.id)
        val m = friendly(u1, null)
        assertEquals(FixtureKind.FRIENDLY, m.fixture.kind); assertEquals(FixtureStatus.PLAYED, m.fixture.status); assertNotNull(m.result)
        val after = clubs.rows.getValue(a.id)
        assertEquals(before.coins + 40, after.coins); assertEquals(before.reputation, after.reputation); assertEquals(1, after.friendliesPlayed)
        friendly(u1, null); friendly(u1, null)
        assertFailsWith<ConflictException> { friendly(u1, null) }
        clock.advanceSeconds(86_400)
        friendly(u1, null)
        val (next, recent) = queries.clubFixtures(a.id)
        assertNotNull(next); assertEquals(FixtureKind.LEAGUE, next.kind); assertEquals(4, recent.fixtures.size)
        val match = queries.match(m.fixture.id)
        assertEquals(m.result!!.primary!!.score, match.result!!.primary!!.score)
        assertEquals(m.result!!.sections, match.result!!.sections)
    }
}
