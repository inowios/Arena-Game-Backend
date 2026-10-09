package com.matchdayclub.domain.usecase.market

import com.matchdayclub.common.time.Clock
import com.matchdayclub.common.tx.NoTx
import com.matchdayclub.common.tx.Tx
import com.matchdayclub.domain.exception.ConflictException
import com.matchdayclub.domain.exception.NotFoundException
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.Athlete
import com.matchdayclub.domain.model.Club
import com.matchdayclub.domain.model.Sport
import com.matchdayclub.domain.repository.AthleteRepository
import com.matchdayclub.domain.repository.ClubRepository
import com.matchdayclub.domain.repository.MarketRepository
import com.matchdayclub.domain.repository.TrainingRepository
import com.matchdayclub.domain.rules.*
import com.matchdayclub.domain.usecase.club.ClubService
import java.time.Instant
import java.util.UUID

data class Listing(val index: Int, val athlete: Athlete, val price: Long, val sold: Boolean)
data class MarketView(val listings: List<Listing>, val refreshAt: Instant, val refreshGold: Int, val club: Club)

class MarketService(private val athletes: AthleteRepository, private val market: MarketRepository, private val clock: Clock) {
    suspend fun view(club: Club): MarketView {
        val now = clock.now(); val period = Market.period(now.epochSecond)
        val sold = market.purchased(club.id, period, club.marketSalt)
        val arena = Arenas.of(club.trophies)
        val listings = (0 until Market.LISTINGS).map { i -> listing(club, period, i, arena, i in sold, now) }
        return MarketView(listings, Instant.ofEpochSecond(Market.periodEnd(now.epochSecond)), Market.REFRESH_GOLD, club)
    }

    fun listing(club: Club, period: Long, index: Int, arena: Arena, sold: Boolean, now: Instant): Listing {
        val q = Market.quality(arena, index)
        // generated for a neutral owner; the buyer's club id is stamped on purchase
        val a = AthleteFactory.create(Market.seed(club.id, period, club.marketSalt, index), club.id, Sport.FOOTBALL, q.first, q.last, null, now)
        return Listing(index, a, Market.price(a), sold)
    }
}

class GetMarketUseCase(private val service: ClubService, private val market: MarketService) {
    suspend operator fun invoke(userId: UUID): MarketView = market.view(service.clubOf(userId))
}

class BuyListingUseCase(private val service: ClubService, private val clubs: ClubRepository, private val athletes: AthleteRepository, private val repo: MarketRepository, private val market: MarketService, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, index: Int): Pair<Athlete, Club> = tx {
        val club = service.lockedClubOf(userId)
        if (index !in 0 until Market.LISTINGS) throw ValidationException("Bad listing")
        val now = clock.now(); val period = Market.period(now.epochSecond)
        val listing = market.listing(club, period, index, Arenas.of(club.trophies), false, now)
        if (athletes.listByClub(club.id).count { it.sport == Sport.FOOTBALL } >= Careers.squadMax(Sport.FOOTBALL)) throw ConflictException("The squad is full — release someone first")
        if (!repo.markPurchased(club.id, period, club.marketSalt, index, listing.athlete.id, now)) throw ConflictException("Already sold")
        val charged = service.charge(club, coins = listing.price)
        val signed = listing.athlete.copy(createdAt = now, updatedAt = now)
        athletes.createAll(listOf(signed))
        signed to charged
    }
}

class RefreshMarketUseCase(private val service: ClubService, private val clubs: ClubRepository, private val market: MarketService, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID): MarketView = tx {
        val club = service.lockedClubOf(userId)
        val charged = service.charge(club, gold = Market.REFRESH_GOLD)
        market.view(clubs.update(charged.copy(marketSalt = club.marketSalt + 1, updatedAt = clock.now())))
    }
}

/** Sells one of your own to the market: half his value in coins; the same squad rules as releasing him. */
class SellAthleteUseCase(private val service: ClubService, private val clubs: ClubRepository, private val athletes: AthleteRepository, private val trainings: TrainingRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, athleteId: UUID): Pair<Long, Club> = tx {
        val club = service.lockedClubOf(userId)
        val a = athletes.findById(athleteId)?.takeIf { it.clubId == club.id && it.retiredAt == null } ?: throw NotFoundException("Athlete not found")
        val rest = athletes.listByClub(club.id).filter { it.sport == a.sport && it.id != a.id }
        val profile = Sections.profile(a.sport)
        if (rest.size < profile.startersCount) throw ValidationException("The section needs at least {0} athletes", profile.startersCount)
        for ((pos, range) in profile.requiredRoles) if (a.position == pos && rest.count { it.position == pos } < range.first) throw ValidationException("You'd have nobody left in this position")
        trainings.listByClub(club.id).filter { it.athleteId == a.id }.forEach { trainings.delete(it.id) }
        val now = clock.now(); val price = Market.sellPrice(a)
        athletes.update(a.copy(retiredAt = now, updatedAt = now))
        price to clubs.update(club.copy(coins = club.coins + price, updatedAt = now))
    }
}
