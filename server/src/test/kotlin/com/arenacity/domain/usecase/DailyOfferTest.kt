package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.CardKind
import com.arenacity.domain.rules.DailyOffers
import com.arenacity.domain.rules.Packs
import com.arenacity.domain.usecase.athlete.PackUseCases
import com.arenacity.domain.usecase.athlete.StarterSquadInitializer
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.club.CreateClubUseCase
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class DailyOfferTest {
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00Z"))
    private val clubs = FakeClubRepository(); private val buildings = FakeBuildingRepository(); private val athletes = FakeAthleteRepository(); private val packs = FakePackRepository()
    private val service = ClubService(clubs, buildings, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock)))
    private val use = PackUseCases(service, clubs, athletes, packs, clock)
    private val u1 = UUID.randomUUID()

    @Test
    fun `one gem pack a UTC day at 30 to 50 percent off, the same all day, ending at the next midnight`() {
        val start = Instant.parse("2026-10-14T00:00:00Z")
        val a = DailyOffers.of(start, 20); val b = DailyOffers.of(Instant.parse("2026-10-14T23:59:59Z"), 20)
        assertEquals(a, b)
        assertEquals(Instant.parse("2026-10-15T00:00:00Z"), a.endsAt)
        val seen = HashSet<CardKind>(); val discounts = HashSet<Int>()
        for (d in 0 until 120) {
            val o = DailyOffers.of(start.plusSeconds(d * 86_400L + 3600), 20)
            assertTrue(o.discountPercent in 30..50); assertTrue(o.spec.gold > 0)
            assertEquals(o.spec.gold, o.oldPrice); assertEquals(Math.round(o.oldPrice * (100 - o.discountPercent) / 100.0).toInt(), o.price)
            assertTrue(o.price < o.oldPrice)
            seen += o.spec.kind; discounts += o.discountPercent
        }
        assertEquals(Packs.ALL.filter { it.gold > 0 }.map { it.kind }.toSet(), seen)   // every gem pack comes round
        assertEquals(DailyOffers.DISCOUNTS.toSet(), discounts)
        // a club's level limits the pick to the packs it has opened; below the first gem pack it sees that one
        for (d in 0 until 30) assertEquals(CardKind.TOURNAMENT, DailyOffers.of(start.plusSeconds(d * 86_400L), 3).spec.kind)
        assertEquals(CardKind.TOURNAMENT, DailyOffers.of(start, 1).spec.kind)
    }

    @Test
    fun `the offer is bought at its price once a day, and again the next day`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        clubs.update(clubs.findById(club.id)!!.copy(level = 3, gold = 1000))
        val view = use.offer(u1)
        assertTrue(view.open); assertFalse(view.bought)
        val (r, after) = use.buyOffer(u1)
        assertTrue(after.bought)
        assertEquals(1000 - view.offer.price, r.club.gold)
        assertEquals(view.offer.price, r.open.priceGold); assertEquals(view.offer.spec.kind, r.open.kind)
        assertEquals(Packs.CARDS_PER_PACK, r.athletes.size)
        assertTrue(use.offer(u1).bought)
        assertFailsWith<ConflictException> { use.buyOffer(u1) }
        assertEquals(1000 - view.offer.price, clubs.findById(club.id)!!.gold)
        clock.advanceSeconds(86_400)
        assertFalse(use.offer(u1).bought)
        val (r2, _) = use.buyOffer(u1)
        assertEquals(1000 - view.offer.price - use.offer(u1).offer.price, r2.club.gold)
    }

    @Test
    fun `a locked or unaffordable offer is refused and not marked bought`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        clubs.update(clubs.findById(club.id)!!.copy(level = 1, gold = 1000))
        assertFalse(use.offer(u1).open)
        assertFailsWith<ValidationException> { use.buyOffer(u1) }
        clubs.update(clubs.findById(club.id)!!.copy(level = 3, gold = 1))
        assertFailsWith<ValidationException> { use.buyOffer(u1) }
        assertFalse(use.offer(u1).bought)
    }
}
