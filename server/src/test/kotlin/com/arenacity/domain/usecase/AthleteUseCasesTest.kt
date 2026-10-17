package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.AttributeKind
import com.arenacity.domain.model.Sport
import com.arenacity.domain.rules.MapSpots
import com.arenacity.domain.usecase.athlete.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.engine.model.Position
import com.arenacity.engine.model.Style
import com.arenacity.engine.model.Tactics
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.*

class AthleteUseCasesTest {
    private val clock = FixedClock()
    private val clubs = FakeClubRepository(); private val athletes = FakeAthleteRepository(); private val trainings = FakeTrainingRepository(); private val lineups = FakeLineupRepository()
    private val spots = FakeClubSpotRepository()
    private val service = ClubService(clubs, clock, spots)
    private val squads = SquadService(service, athletes, trainings, clock)
    private val create = CreateClubUseCase(clubs, clock, listOf(StarterSquadInitializer(athletes, clock)))
    private val claim = ClaimAcademyUseCase(service, clubs, athletes, clock)
    private val start = StartTrainingUseCase(service, squads, athletes, trainings, clock)
    private val complete = CompleteTrainingUseCase(service, squads, trainings, clock)
    private val heal = HealAthleteUseCase(service, squads, clubs, athletes, clock)
    private val getLineup = GetLineupUseCase(service, squads, lineups, clock)
    private val setLineup = SetLineupUseCase(service, squads, lineups, clock)
    private val user = UUID.randomUUID()

    /** A club with the map's [open] spots open: its stadium level is 1 + their number. */
    private fun setup(vararg open: String) = runBlocking {
        create(user, "Lions", "#AA0000", "#FFFFFF", "UTC")
        clubs.update(clubs.rows.values.first().copy(coins = 50_000, gold = 500, level = 3)); spots.open(clubs.rows.values.first().id, *open)
    }

    @Test
    fun `new club gets a starter squad and a default lineup`() = runBlocking<Unit> {
        setup()
        val (l, squad) = getLineup(user, Sport.FOOTBALL)
        assertEquals(14, squad.size); assertTrue(l.isDefault); assertEquals(11, l.starters.size); assertEquals(3, l.bench.size)
        assertEquals(1, l.starters.count { id -> squad.first { it.id == id }.position == Position.GK })
    }

    @Test
    fun `academy claim needs the academy open and respects cooldown`() = runBlocking<Unit> {
        setup()
        clubs.update(clubs.rows.values.first().copy(academyNextAt = null))
        assertEquals("Open the academy first", assertFailsWith<ValidationException> { claim(user) }.template)
        spots.open(clubs.rows.values.first().id, MapSpots.ACADEMY)        // stadium 2
        val a = claim(user)
        assertEquals(15, athletes.rows.size); assertTrue(a.overall in 30..70)
        assertFailsWith<ConflictException> { claim(user) }
        clock.advanceSeconds(11 * 3600)   // stadium 2: a graduate every 11 hours
        claim(user); assertEquals(16, athletes.rows.size)
    }

    @Test
    fun `training raises an attribute after an hour and uses slots`() = runBlocking<Unit> {
        setup()   // training belongs to the stadium: stadium 1 has one slot, +1 a training
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
        setup()
        val gk = athletes.rows.values.first { it.position == com.arenacity.engine.model.Position.GK }
        assertFailsWith<ValidationException> { start(user, gk.id, null) }
        val a = athletes.rows.values.first { it.position == com.arenacity.engine.model.Position.FWD }
        val t = start(user, a.id, null)
        assertTrue(t.drill); assertEquals("SKILL", t.kindKey)
        clock.advanceSeconds(3600)
        assertFailsWith<ConflictException> { complete(user, t.id) }
        clock.advanceSeconds(3600)
        val done = complete(user, t.id)
        assertEquals(a.skillPoints + 1, done.skillPoints); assertEquals(a.technique, done.technique)
    }

    @Test
    fun `training respects fatigue and the stadium's cap`() = runBlocking<Unit> {
        setup(MapSpots.ACADEMY, MapSpots.HOSPITAL)   // stadium 3
        val a = athletes.rows.values.first()
        athletes.update(a.copy(fatigue = 90, fatigueAt = clock.now()))
        assertFailsWith<ConflictException> { start(user, a.id, AttributeKind.MENTAL) }
        athletes.update(a.copy(fatigue = 0, mental = 68))
        assertFailsWith<ValidationException> { start(user, a.id, AttributeKind.MENTAL) }   // stadium 3 caps attributes at 68
        Unit
    }

    @Test
    fun `the hospital heals with cooldown once it is open`() = runBlocking<Unit> {
        setup()
        val a = athletes.rows.values.first()
        athletes.update(a.copy(injuredUntil = clock.now().plusSeconds(86_400), fatigue = 50, fatigueAt = clock.now()))
        assertEquals("Open the hospital first", assertFailsWith<ValidationException> { heal(user, a.id) }.template)
        spots.open(clubs.rows.values.first().id, MapSpots.HOSPITAL)       // stadium 2
        val healed = heal(user, a.id)
        assertNull(healed.injuredUntil); assertEquals(0, healed.fatigue)
        val b = athletes.rows.values.drop(1).first()
        athletes.update(b.copy(fatigue = 30, fatigueAt = clock.now()))
        assertFailsWith<ConflictException> { heal(user, b.id) }
        clock.advanceSeconds(4 * 3600)                                     // stadium 2: 4 h cooldown
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
