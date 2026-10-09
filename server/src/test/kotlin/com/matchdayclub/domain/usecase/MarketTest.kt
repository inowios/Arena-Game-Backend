package com.matchdayclub.domain.usecase

import com.matchdayclub.domain.exception.ConflictException
import com.matchdayclub.domain.exception.InsufficientFundsException
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.rules.Market
import com.matchdayclub.domain.usecase.athlete.StarterSquadInitializer
import com.matchdayclub.domain.usecase.club.ClubService
import com.matchdayclub.domain.usecase.club.CreateClubUseCase
import com.matchdayclub.domain.usecase.market.*
import com.matchdayclub.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class MarketTest {
    private val clock = FixedClock(Instant.parse("2026-10-09T10:00:00Z"))
    private val clubs = FakeClubRepository(); private val buildings = FakeBuildingRepository(); private val athletes = FakeAthleteRepository(); private val trainings = FakeTrainingRepository()
    private val repo = FakeMarketRepository()
    private val clubService = ClubService(clubs, buildings, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock)))
    private val service = MarketService(athletes, repo, clock)
    private val get = GetMarketUseCase(clubService, service)
    private val buy = BuyListingUseCase(clubService, clubs, athletes, repo, service, clock)
    private val refresh = RefreshMarketUseCase(clubService, clubs, service, clock)
    private val sell = SellAthleteUseCase(clubService, clubs, athletes, trainings, clock)
    private val u1 = UUID.randomUUID()

    @Test
    fun `listings are stable within a period, change after it, and a listing sells once`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val a = get(u1); val b = get(u1)
        assertEquals(Market.LISTINGS, a.listings.size)
        assertEquals(a.listings.map { it.athlete.name }, b.listings.map { it.athlete.name })
        assertTrue(a.listings.all { it.price > 0 && !it.sold })
        clubs.rows[club.id] = clubs.rows.getValue(club.id).copy(coins = 1_000_000)
        val before = athletes.listByClub(club.id).size
        val (signed, after) = buy(u1, 3)
        assertEquals(a.listings[3].athlete.name, signed.name); assertEquals(before + 1, athletes.listByClub(club.id).size)
        assertEquals(1_000_000 - a.listings[3].price, after.coins)
        assertTrue(get(u1).listings[3].sold)
        assertFailsWith<ConflictException> { buy(u1, 3) }
        clock.advanceSeconds(Market.PERIOD_SECONDS)
        val c = get(u1)
        assertNotEquals(a.listings.map { it.athlete.name }, c.listings.map { it.athlete.name }); assertFalse(c.listings[3].sold)
    }

    @Test
    fun `no coins no player, gold refreshes, selling pays half`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        clubs.rows[club.id] = clubs.rows.getValue(club.id).copy(coins = 0, gold = 10)
        assertFailsWith<InsufficientFundsException> { buy(u1, 0) }
        val before = get(u1).listings.map { it.athlete.name }
        val refreshed = refresh(u1)
        assertNotEquals(before, refreshed.listings.map { it.athlete.name }); assertEquals(10 - Market.REFRESH_GOLD, refreshed.club.gold)
        val squad = athletes.listByClub(club.id)
        val mid = squad.filter { it.position == com.matchdayclub.engine.model.Position.MID }
        val (coins, c2) = sell(u1, mid.first().id)
        assertEquals(Market.sellPrice(mid.first()), coins); assertEquals(coins, c2.coins)
        assertEquals(squad.size - 1, athletes.listByClub(club.id).size)
        // the last keeper cannot be sold
        val gks = athletes.listByClub(club.id).filter { it.position == com.matchdayclub.engine.model.Position.GK }
        sell(u1, gks[0].id)
        assertFailsWith<ValidationException> { sell(u1, gks[1].id) }
    }
}
