package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.*
import com.arenacity.domain.rules.BuildingType
import com.arenacity.domain.rules.Sections
import com.arenacity.domain.rules.Standings
import com.arenacity.domain.usecase.athlete.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.domain.usecase.league.*
import com.arenacity.engine.model.Position
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class SectionsTest {
    private val clock = FixedClock(Instant.parse("2026-10-09T10:00:00Z"))
    private val users = FakeUserRepository(); private val clubs = FakeClubRepository(); private val buildings = FakeBuildingRepository()
    private val athletes = FakeAthleteRepository(); private val trainings = FakeTrainingRepository(); private val lineups = FakeLineupRepository()
    private val leagues = FakeLeagueRepository(); private val fixtures = FakeFixtureRepository()
    private val sections = SectionService(buildings, athletes)
    private val service = ClubService(clubs, buildings, clock)
    private val bots = BotService(users, clubs, athletes, clock)
    private val leagueService = LeagueService(leagues, fixtures, clubs, bots, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock), JoinPublicLeagueInitializer(clubs, leagueService, clock)))
    private val place = PlaceBuildingUseCase(service, buildings, clock)
    private val speed = SpeedUpUseCase(service, buildings, clock)
    private val squads = SquadService(service, athletes, trainings, clock)
    private val academy = ClaimAcademyUseCase(service, clubs, athletes, clock, sections = sections)
    private val getLineup = GetLineupUseCase(service, squads, lineups, clock)
    private val setLineup = SetLineupUseCase(service, squads, lineups, clock)
    private val runner = MatchdayRunner(leagues, fixtures, clubs, athletes, LineupService(athletes, lineups), leagueService, clock, sections = sections)
    private val user = UUID.randomUUID()

    /** A club that kept a basketball squad from the sport town (its arena went with V44, 15 October 2026). */
    private fun clubWithHoopers(): Club = runBlocking {
        val c = create(user, "Hoops FC", "#AA0000", "#FFFFFF", "UTC")
        clubs.update(clubs.rows.getValue(c.id).copy(level = 4, coins = 50_000, gold = 500)); buildings.setStadium(c.id, 4)
        athletes.createAll(com.arenacity.domain.rules.AthleteFactory.starterSquad(c.id, Sport.BASKETBALL, clock.now()))
        clubs.rows.getValue(c.id)
    }

    @Test
    fun `football is the one section, an old basketball squad sits out`() = runBlocking<Unit> {
        val c = clubWithHoopers()
        assertEquals(8, athletes.rows.values.count { it.clubId == c.id && it.sport == Sport.BASKETBALL })
        assertEquals(listOf(Sport.FOOTBALL), sections.active(c))
        assertEquals(listOf(Sport.FOOTBALL), Sections.ORDER)
        // bots field football at every division
        for (d in 1..8) assertEquals(listOf(Sport.FOOTBALL), Sections.botSections(d))
        // the academy graduates footballers only
        place(user, BuildingType.ACADEMY, 8, 26, 0).let { speed(user, it.id) }
        assertFailsWith<ValidationException> { academy(user, Sport.BASKETBALL) }
        assertEquals(Sport.FOOTBALL, academy(user, Sport.FOOTBALL).sport)
    }

    @Test
    fun `matchday plays football only`() = runBlocking<Unit> {
        val c = clubWithHoopers()
        val played = runner.runDueRounds(Instant.parse("2026-10-09T20:00:00Z"))
        assertEquals(4, played)
        val mine = fixtures.rows.values.single { it.involves(c.id) && it.round == 1 }
        val result = MatchdayResult.parse(mine.resultJson!!)
        assertEquals(listOf("FOOTBALL"), result.sections.map { it.sport })
        assertEquals(result.homePoints, mine.homePoints); assertEquals(result.awayPoints, mine.awayPoints)
        val table = Standings.table(fixtures.rows.values.toList(), leagues.members(mine.leagueId!!))
        assertEquals(mine.pointsFor(c.id), table.first { it.clubId == c.id }.points)
        assertTrue(fixtures.rows.values.filter { it.round == 1 }.all { MatchdayResult.parse(it.resultJson!!).sections.size == 1 })
    }

    @Test
    fun `injured starter is replaced by a fit player of the same role`() = runBlocking<Unit> {
        val c = create(user, "Subs FC", "#AA0000", "#FFFFFF", "UTC")
        val (l, squad) = getLineup(user, Sport.FOOTBALL)
        setLineup(user, Sport.FOOTBALL, l.starters, l.bench, l.tactics)
        val victim = squad.first { it.id == l.starters.first { id -> squad.first { a -> a.id == id }.position == Position.DEF } }
        athletes.update(victim.copy(injuredUntil = clock.now().plusSeconds(86_400)))
        val effective = LineupService(athletes, lineups).lineup(clubs.rows.getValue(c.id), Sport.FOOTBALL, clock.now())
        assertFalse(victim.id in effective.starters)
        assertEquals(11, effective.starters.size)
        val sub = athletes.rows.getValue(effective.starters[l.starters.indexOf(victim.id)])
        val spareDefender = squad.any { it.position == Position.DEF && it.id !in l.starters }
        if (spareDefender) assertEquals(Position.DEF, sub.position) else assertNotEquals(Position.GK, sub.position)
        assertFalse(sub.isInjured(clock.now()))
    }

    @Test
    fun `old football-only results still read`() {
        val legacy = """{"sport":"football","seed":1,"homeTeam":"h","awayTeam":"a","score":{"home":2,"away":2},"events":[],"homePossession":50,"homeShots":5,"awayShots":5}"""
        val r = MatchdayResult.parse(legacy)
        assertEquals(1, r.sections.size); assertEquals(1, r.homePoints); assertEquals(1, r.awayPoints); assertEquals(2, r.primary!!.score.home)
        assertEquals(Sections.points(2, 1), 3)
    }
}
