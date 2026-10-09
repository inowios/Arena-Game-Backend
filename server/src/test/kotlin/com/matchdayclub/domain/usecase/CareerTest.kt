package com.matchdayclub.domain.usecase

import com.matchdayclub.domain.exception.ConflictException
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.rules.*
import com.matchdayclub.domain.usecase.athlete.*
import com.matchdayclub.domain.usecase.club.*
import com.matchdayclub.domain.usecase.league.*
import com.matchdayclub.engine.model.Position
import com.matchdayclub.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.*

class CareerTest {
    private val clock = FixedClock(Instant.parse("2026-10-09T10:00:00Z"))
    private val users = FakeUserRepository(); private val clubs = FakeClubRepository(); private val buildings = FakeBuildingRepository()
    private val athletes = FakeAthleteRepository(); private val trainings = FakeTrainingRepository(); private val lineups = FakeLineupRepository()
    private val leagues = FakeLeagueRepository(); private val fixtures = FakeFixtureRepository()
    private val service = ClubService(clubs, buildings, clock)
    private val sections = SectionService(buildings, athletes)
    private val bots = BotService(users, clubs, athletes, clock)
    private val leagueService = LeagueService(leagues, fixtures, clubs, bots, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock), JoinPublicLeagueInitializer(clubs, leagueService, clock)))
    private val place = PlaceBuildingUseCase(service, buildings, clock)
    private val speed = SpeedUpUseCase(service, buildings, clock)
    private val careers = CareerService(athletes, trainings, clock)
    private val release = ReleaseAthleteUseCase(service, athletes, trainings, clock)
    private val scouting = ScoutingUseCases(service, clubs, athletes, sections, clock)
    private val squads = SquadService(service, athletes, trainings, clock)
    private val train = StartTrainingUseCase(service, squads, athletes, trainings, clock)
    private val runner = MatchdayRunner(leagues, fixtures, clubs, athletes, LineupService(athletes, lineups), leagueService, clock, sections = sections)
    private val user = UUID.randomUUID()

    private fun club() = clubs.rows.values.first { it.userId == user }
    private fun squad(sport: Sport = Sport.FOOTBALL) = athletes.rows.values.filter { it.clubId == club().id && it.sport == sport && it.retiredAt == null }

    private fun setup() = runBlocking {
        create(user, "Careers FC", "#AA0000", "#FFFFFF", "UTC")
        clubs.update(club().copy(level = 3, coins = 50_000, gold = 500))
    }

    @Test
    fun `young athletes grow up to potential, veterans decline`() {
        val now = clock.now()
        val kid = AthleteFactory.create(1, UUID.randomUUID(), Sport.FOOTBALL, 45, 45, Position.MID, now, age = 18)
        assertTrue(kid.potential > kid.attributeAverage + 5, "young players have room: ${kid.potential} vs ${kid.attributeAverage}")
        fun sum(a: Athlete) = a.attack + a.defense + a.physical + a.technique + a.mental
        val grown = Careers.age(kid, Random(1))
        assertEquals(19, grown.age); assertEquals(sum(kid) + 4, sum(grown), "+4 points at 19")
        val capped = Careers.age(kid.copy(potential = kid.attributeAverage), Random(1))
        assertTrue(sum(capped) <= maxOf(sum(kid), kid.attributeAverage * 5), "no growth past potential")
        val vet = AthleteFactory.create(2, UUID.randomUUID(), Sport.FOOTBALL, 70, 70, Position.DEF, now, age = 31)
        val old = Careers.age(vet, Random(2))
        assertTrue(old.physical < vet.physical); assertEquals(32, old.age)
        assertEquals(0.0, Careers.retirementChance(33)); assertEquals(1.0, Careers.retirementChance(36))
        assertEquals(55, Careers.moraleAfter(50, true, 3)); assertEquals(48, Careers.moraleAfter(50, false, 0)); assertEquals(Careers.MORALE_MIN, Careers.moraleAfter(21, true, 0))
    }

    @Test
    fun `season end ages the squad, retires veterans and brings youth in`() = runBlocking<Unit> {
        setup()
        val veteran = squad().first { it.position == Position.DEF }
        athletes.update(veteran.copy(age = 35))
        val before = squad().size
        val result = careers.seasonEnd(club(), 1)
        assertEquals(listOf(veteran.id), result.retired.map { it.id })
        assertEquals(1, result.youth.size); assertEquals(Position.DEF, result.youth.single().position); assertTrue(result.youth.single().age in 17..18)
        assertEquals(before, squad().size)
        assertNotNull(athletes.rows.getValue(veteran.id).retiredAt)
        assertTrue(squad().none { it.id == veteran.id })
        assertTrue(veteran.id in athletes.namesByClubs(listOf(club().id)), "retired players keep their names in old highlights")
        assertTrue(squad().filter { it.id != result.youth.single().id }.all { it.age >= 18 })
    }

    @Test
    fun `release keeps a playable section`() = runBlocking<Unit> {
        setup()
        val keepers = squad().filter { it.position == Position.GK }
        assertEquals(2, keepers.size)
        release(user, keepers[0].id)
        assertFailsWith<ValidationException> { release(user, keepers[1].id) }   // last goalkeeper
        squad().filter { it.position != Position.GK }.take(2).forEach { release(user, it.id) }
        assertEquals(11, squad().size)
        assertFailsWith<ValidationException> { release(user, squad().first { it.position == Position.MID }.id) }
        Unit
    }

    @Test
    fun `scouting needs an academy and signs one player a day`() = runBlocking<Unit> {
        setup()
        assertFailsWith<ValidationException> { scouting.report(user) }
        place(user, BuildingType.ACADEMY, 2, 10, 0).let { speed(user, it.id) }
        val r = scouting.report(user)
        assertEquals(listOf("PROSPECT", "PRIME", "VETERAN"), r.offers.map { it.kind })
        assertTrue(r.offers[0].athlete.age in 17..19 && r.offers[2].athlete.age in 30..32)
        assertEquals(r.offers.map { it.athlete.name }, scouting.report(user).offers.map { it.athlete.name }, "same offers all day")
        val before = squad().size
        val signed = scouting.sign(user, 1)
        assertEquals(r.offers[1].athlete.name, signed.name); assertEquals(before + 1, squad().size)
        assertFailsWith<ConflictException> { scouting.sign(user, 0) }
        assertTrue(scouting.report(user).signedToday)
        clock.advanceSeconds(86_400)
        assertNotEquals(r.offers.map { it.athlete.name }, scouting.report(user).offers.map { it.athlete.name })
        // squad limit
        repeat(Careers.squadMax(Sport.FOOTBALL) - squad().size) { athletes.createAll(listOf(AthleteFactory.create(100L + it, club().id, Sport.FOOTBALL, 40, 45, null, clock.now()))) }
        assertFailsWith<ConflictException> { scouting.sign(user, 0) }
        Unit
    }

    @Test
    fun `training stops at potential`() = runBlocking<Unit> {
        setup()
        place(user, BuildingType.TRAINING_CENTER, 2, 10, 0).let { speed(user, it.id) }
        val a = squad().first { it.position == Position.MID && it.attack < 58 }
        athletes.update(a.copy(potential = a.attributeAverage))
        assertFailsWith<ValidationException> { train(user, a.id, AttributeKind.ATTACK) }
        athletes.update(a.copy(potential = a.attributeAverage + 3))
        train(user, a.id, AttributeKind.ATTACK); Unit
    }

    @Test
    fun `league matchdays move morale with playing time and results`() = runBlocking<Unit> {
        setup()
        runner.runDueRounds(Instant.parse("2026-10-09T20:00:00Z"))
        val mine = fixtures.rows.values.single { it.involves(club().id) && it.round == 1 }
        val points = mine.pointsFor(club().id)!!
        val moved = squad().map { it.morale }.toSet()
        val expectedStarter = Careers.moraleAfter(50, true, points)
        assertTrue(expectedStarter in moved, "starters: $moved")
        assertTrue(48 in moved, "bench players lose a little morale: $moved")
    }
}
