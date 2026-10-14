package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.rules.Energy
import com.arenacity.domain.usecase.athlete.CardMatchUseCases
import com.arenacity.domain.usecase.athlete.StarterSquadInitializer
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.club.CreateClubUseCase
import com.arenacity.domain.usecase.club.EnergyUseCases
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class EnergyTest {
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00Z"))
    private val clubs = FakeClubRepository(); private val buildings = FakeBuildingRepository(); private val athletes = FakeAthleteRepository()
    private val service = ClubService(clubs, buildings, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock)))
    private val energy = EnergyUseCases(service, clubs, clock)
    private val cards = CardMatchUseCases(service, clubs, athletes, clock)
    private val u1 = UUID.randomUUID()

    @Test
    fun `energy refills by one every six minutes up to thirty, computed from the stored amount`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val full = Energy.state(club, clock.now())
        assertEquals(30, full.current); assertNull(full.nextAt); assertNull(full.fullAt)
        val t0 = clock.now()
        val spent = Energy.spend(club, 7, t0)!!
        assertEquals(23, Energy.state(spent, t0).current)
        assertEquals(t0.plusSeconds(360), Energy.state(spent, t0).nextAt)
        assertEquals(t0.plusSeconds(7 * 360), Energy.state(spent, t0).fullAt)
        assertEquals(23, Energy.state(spent, t0.plusSeconds(359)).current)
        assertEquals(24, Energy.state(spent, t0.plusSeconds(360)).current)
        assertEquals(26, Energy.state(spent, t0.plusSeconds(3 * 360 + 100)).current)
        assertEquals(30, Energy.state(spent, t0.plusSeconds(10_000)).current)
        assertNull(Energy.state(spent, t0.plusSeconds(10_000)).nextAt)
        // spending again keeps the part of the six minutes already counted
        val t1 = t0.plusSeconds(400)   // 24 now, 40 s into the next one
        val again = Energy.spend(spent, 5, t1)!!
        assertEquals(19, Energy.state(again, t1).current)
        assertEquals(t0.plusSeconds(720), Energy.state(again, t1).nextAt)
        assertNull(Energy.spend(again, 20, t1))
        // spending from full starts the clock at that moment
        val fromFull = Energy.spend(spent, 1, t0.plusSeconds(10_000))!!
        assertEquals(29, Energy.state(fromFull, t0.plusSeconds(10_000)).current)
        assertEquals(t0.plusSeconds(10_360), Energy.state(fromFull, t0.plusSeconds(10_000)).nextAt)
    }

    @Test
    fun `a card match start costs five, refused with a clear error when short, and gems refill it`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        assertEquals(25, cards.start(u1).current)
        assertEquals(25, energy.state(u1).current)
        repeat(5) { cards.start(u1) }
        assertEquals(0, energy.state(u1).current)
        val e = assertFailsWith<ValidationException> { cards.start(u1) }
        assertEquals("Not enough energy", e.template)
        assertEquals("Недостаточно энергии — подождите, пока она восстановится, или пополните её", e.localized("ru"))
        clock.advanceSeconds(5 * Energy.REGEN_SECONDS)
        assertEquals(0, cards.start(u1).current)
        // refill: 40 gems, to full; not when full, not without the gems
        clubs.update(clubs.findById(club.id)!!.copy(gold = 39))
        assertFailsWith<ValidationException> { energy.refill(u1) }
        clubs.update(clubs.findById(club.id)!!.copy(gold = 100))
        val (saved, st) = energy.refill(u1)
        assertEquals(30, st.current); assertEquals(100 - Energy.REFILL_GOLD, saved.gold)
        assertFailsWith<ConflictException> { energy.refill(u1) }
        assertEquals(60, clubs.findById(club.id)!!.gold)
    }
}
