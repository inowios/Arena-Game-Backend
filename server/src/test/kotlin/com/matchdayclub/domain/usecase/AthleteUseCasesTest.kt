package com.matchdayclub.domain.usecase

import com.matchdayclub.domain.exception.ConflictException
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.AttributeKind
import com.matchdayclub.domain.model.Sport
import com.matchdayclub.domain.rules.BuildingType
import com.matchdayclub.domain.usecase.athlete.*
import com.matchdayclub.domain.usecase.club.*
import com.matchdayclub.engine.model.Position
import com.matchdayclub.engine.model.Style
import com.matchdayclub.engine.model.Tactics
import com.matchdayclub.testutil.*
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.*

class AthleteUseCasesTest {
    private val clock = FixedClock()
    private val clubs = FakeClubRepository(); private val buildings = FakeBuildingRepository(); private val athletes = FakeAthleteRepository(); private val trainings = FakeTrainingRepository(); private val lineups = FakeLineupRepository()
    private val service = ClubService(clubs, buildings, clock)
    private val squads = SquadService(service, athletes, trainings, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock)))
    private val place = PlaceBuildingUseCase(service, buildings, clock)
    private val speed = SpeedUpUseCase(service, buildings, clock)
    private val claim = ClaimAcademyUseCase(service, clubs, athletes, clock)
    private val start = StartTrainingUseCase(service, squads, athletes, trainings, clock)
    private val complete = CompleteTrainingUseCase(service, squads, trainings, clock)
    private val heal = HealAthleteUseCase(service, squads, clubs, athletes, clock)
    private val getLineup = GetLineupUseCase(service, squads, lineups, clock)
    private val setLineup = SetLineupUseCase(service, squads, lineups, clock)
    private val user = UUID.randomUUID()

    private fun setup(vararg types: BuildingType) = runBlocking {
        create(user, "Lions", "#AA0000", "#FFFFFF", "UTC")
        clubs.update(clubs.rows.values.first().copy(coins = 50_000, gold = 500, level = 3)); buildings.setStadium(clubs.rows.values.first().id, 3)
        types.forEachIndexed { i, t -> val b = place(user, t, 4 + i * 7, 4, 0); speed(user, b.id) }   // the big footprints: a row along the top edge
    }

    @Test
    fun `new club gets a starter squad and a default lineup`() = runBlocking<Unit> {
        setup()
        val (l, squad) = getLineup(user, Sport.FOOTBALL)
        assertEquals(14, squad.size); assertTrue(l.isDefault); assertEquals(11, l.starters.size); assertEquals(3, l.bench.size)
        assertEquals(1, l.starters.count { id -> squad.first { it.id == id }.position == Position.GK })
    }

    @Test
    fun `academy claim respects cooldown`() = runBlocking<Unit> {
        setup(BuildingType.ACADEMY)
        clubs.update(clubs.rows.values.first().copy(academyNextAt = null))
        val a = claim(user)
        assertEquals(15, athletes.rows.size); assertTrue(a.overall in 30..70)
        assertFailsWith<ConflictException> { claim(user) }
        clock.advanceSeconds(8 * 3600)
        claim(user); assertEquals(16, athletes.rows.size)
    }

    @Test
    fun `training raises an attribute after an hour and uses slots`() = runBlocking<Unit> {
        setup(BuildingType.TRAINING_CENTER)
        val a = athletes.rows.values.first { it.attack < 60 }
        val t = start(user, a.id, AttributeKind.ATTACK)
        assertEquals(15, athletes.rows.getValue(a.id).fatigue)
        assertFailsWith<ConflictException> { start(user, athletes.rows.values.first { it.id != a.id }.id, AttributeKind.ATTACK) }   // 1 slot
        assertFailsWith<ConflictException> { complete(user, t.id) }
        clock.advanceSeconds(3600)
        val done = complete(user, t.id)
        assertEquals(a.attack + 1, done.attack); assertTrue(trainings.rows.isEmpty())
    }

    @Test
    fun `a technique drill takes two hours and pays a skill point`() = runBlocking<Unit> {
        setup(BuildingType.TRAINING_CENTER)
        val gk = athletes.rows.values.first { it.position == com.matchdayclub.engine.model.Position.GK }
        assertFailsWith<ValidationException> { start(user, gk.id, null) }
        val a = athletes.rows.values.first { it.position == com.matchdayclub.engine.model.Position.FWD }
        val t = start(user, a.id, null)
        assertTrue(t.drill); assertEquals("SKILL", t.kindKey)
        clock.advanceSeconds(3600)
        assertFailsWith<ConflictException> { complete(user, t.id) }
        clock.advanceSeconds(3600)
        val done = complete(user, t.id)
        assertEquals(a.skillPoints + 1, done.skillPoints); assertEquals(a.technique, done.technique)
    }

    @Test
    fun `training needs a centre and respects fatigue and cap`() = runBlocking<Unit> {
        setup()
        val a = athletes.rows.values.first()
        assertFailsWith<ValidationException> { start(user, a.id, AttributeKind.ATTACK) }
        val tc = place(user, BuildingType.TRAINING_CENTER, 4, 20, 0); speed(user, tc.id)
        athletes.update(a.copy(fatigue = 90, fatigueAt = clock.now()))
        assertFailsWith<ConflictException> { start(user, a.id, AttributeKind.MENTAL) }
        athletes.update(a.copy(fatigue = 0, mental = 68))
        assertFailsWith<ValidationException> { start(user, a.id, AttributeKind.MENTAL) }   // stadium 3 caps attributes at 68
        Unit
    }

    @Test
    fun `medical centre heals with cooldown`() = runBlocking<Unit> {
        setup(BuildingType.MEDICAL_CENTER)
        val a = athletes.rows.values.first()
        athletes.update(a.copy(injuredUntil = clock.now().plusSeconds(86_400), fatigue = 50, fatigueAt = clock.now()))
        val healed = heal(user, a.id)
        assertNull(healed.injuredUntil); assertEquals(0, healed.fatigue)
        val b = athletes.rows.values.drop(1).first()
        athletes.update(b.copy(fatigue = 30, fatigueAt = clock.now()))
        assertFailsWith<ConflictException> { heal(user, b.id) }
        clock.advanceSeconds(5 * 3600)                                     // medical centre L1: 5 h cooldown
        athletes.update(b.copy(fatigue = 30, fatigueAt = clock.now()))   // tired again once the cooldown has passed
        heal(user, b.id)
        Unit
    }

    @Test
    fun `set lineup validates and engine bridge maps injuries`() = runBlocking<Unit> {
        setup()
        val squad = athletes.rows.values.toList()
        val gk = squad.first { it.position == Position.GK }
        val out = squad.filter { it.position != Position.GK }
        val starters = listOf(gk.id) + out.take(10).map { it.id }
        val l = setLineup(user, Sport.FOOTBALL, starters, out.drop(10).map { it.id }, Tactics(style = Style.ATTACK))
        assertFalse(l.isDefault); assertEquals(Style.ATTACK, getLineup(user, Sport.FOOTBALL).first.tactics.style)
        assertFailsWith<ValidationException> { setLineup(user, Sport.FOOTBALL, starters.drop(1), emptyList(), Tactics()) }
        assertFailsWith<ValidationException> { setLineup(user, Sport.FOOTBALL, out.take(11).map { it.id }, emptyList(), Tactics()) }   // no GK
        assertFailsWith<ValidationException> { setLineup(user, Sport.FOOTBALL, starters, listOf(gk.id), Tactics()) }                  // duplicate
        athletes.update(out[0].copy(injuredUntil = clock.now().plusSeconds(3600)))
        val eng = EngineBridge.toEngineLineup("h", l, athletes.rows.values.toList(), clock.now())
        assertEquals(11, eng.starters.size); assertTrue(eng.starters.first { it.id == out[0].id.toString() }.injured)
    }
}
