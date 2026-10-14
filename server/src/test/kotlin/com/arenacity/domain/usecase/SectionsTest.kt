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
    // the basketball zone open: its island (the shipped mask's) is the club's land, where the arena stands
    private val zones = FakeZoneRepository()
    private val service = ClubService(clubs, buildings, clock, listOf(SectionSquadOnCourt(athletes, clock))).apply { openZones = { id -> openZoneSports(zones, id, clock.now()) } }
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

    private fun clubWithCourt(): Club = runBlocking {
        val c = create(user, "Hoops FC", "#AA0000", "#FFFFFF", "UTC")
        clubs.update(clubs.rows.getValue(c.id).copy(level = 4, coins = 50_000, gold = 500)); buildings.setStadium(c.id, 4)
        zones.open(c.id, ZoneSport.BASKETBALL)
        val isle = service.lands.island(ZoneSport.BASKETBALL)!!
        val (x, y) = isle.cells.first { (x, y) -> com.arenacity.domain.rules.Grid.aligned(x, y) && isle.fits(com.arenacity.domain.rules.BuildingCatalog.spec(com.arenacity.domain.rules.BuildingType.BASKETBALL_ARENA).let { s -> com.arenacity.domain.rules.Footprint(x, y, s.width, s.height) }) }
        val court = place(user, BuildingType.BASKETBALL_ARENA, x, y, 0)
        assertEquals(listOf(Sport.FOOTBALL), sections.active(clubs.rows.getValue(c.id)), "an arena under construction is not a section yet")
        speed(user, court.id)
        clubs.rows.getValue(c.id)
    }

    @Test
    fun `finishing a basketball arena opens the section with a starter squad, once`() = runBlocking<Unit> {
        val c = clubWithCourt()
        val hoopers = athletes.rows.values.filter { it.clubId == c.id && it.sport == Sport.BASKETBALL }
        assertEquals(8, hoopers.size)
        assertEquals(setOf(Position.G, Position.F, Position.C), hoopers.map { it.position }.toSet())
        service.state(user); service.state(user)
        assertEquals(8, athletes.rows.values.count { it.clubId == c.id && it.sport == Sport.BASKETBALL })
        assertEquals(listOf(Sport.FOOTBALL, Sport.BASKETBALL), sections.active(c))
    }

    @Test
    fun `academy graduates per section, only for open sections`() = runBlocking<Unit> {
        clubWithCourt()
        place(user, BuildingType.ACADEMY, 8, 26, 0).let { speed(user, it.id) }
        val g = academy(user, Sport.BASKETBALL)
        assertEquals(Sport.BASKETBALL, g.sport); assertTrue(g.position in setOf(Position.G, Position.F, Position.C))
        clock.advanceSeconds(9 * 3600)
        assertFailsWith<ValidationException> { academy(user, Sport.TENNIS) }
        Unit
    }

    @Test
    fun `basketball lineup has five of its own athletes`() = runBlocking<Unit> {
        clubWithCourt()
        val (l, squad) = getLineup(user, Sport.BASKETBALL)
        assertEquals(5, l.starters.size); assertTrue(squad.all { it.sport == Sport.BASKETBALL })
        assertTrue(l.starters.map { id -> squad.first { it.id == id }.position }.contains(Position.C))
        val footballer = athletes.rows.values.first { it.sport == Sport.FOOTBALL }
        assertFailsWith<ValidationException> { setLineup(user, Sport.BASKETBALL, l.starters.dropLast(1) + footballer.id, emptyList(), l.tactics) }
        setLineup(user, Sport.BASKETBALL, l.starters, l.bench, l.tactics); Unit
    }

    @Test
    fun `matchday plays every section and a missing section is a walkover`() = runBlocking<Unit> {
        val c = clubWithCourt()
        val played = runner.runDueRounds(Instant.parse("2026-10-09T20:00:00Z"))
        assertEquals(4, played)
        val mine = fixtures.rows.values.single { it.involves(c.id) && it.round == 1 }
        val result = MatchdayResult.parse(mine.resultJson!!)
        assertEquals(listOf("FOOTBALL", "BASKETBALL"), result.sections.map { it.sport })
        val bb = result.section(Sport.BASKETBALL)!!
        assertNotNull(bb.walkover)                                       // division-1 bots field football only
        val iAmHome = mine.homeClubId == c.id
        assertEquals(if (iAmHome) "HOME" else "AWAY", bb.walkover)
        assertEquals(3, if (iAmHome) bb.homePoints else bb.awayPoints)
        assertEquals(result.homePoints, mine.homePoints); assertEquals(result.awayPoints, mine.awayPoints)
        val table = Standings.table(fixtures.rows.values.toList(), leagues.members(mine.leagueId!!))
        val row = table.first { it.clubId == c.id }
        assertEquals(mine.pointsFor(c.id), row.points)
        assertTrue(row.points >= 3)
        // other fixtures of the round are football only
        assertTrue(fixtures.rows.values.filter { it.round == 1 && !it.involves(c.id) }.all { MatchdayResult.parse(it.resultJson!!).sections.size == 1 })
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
