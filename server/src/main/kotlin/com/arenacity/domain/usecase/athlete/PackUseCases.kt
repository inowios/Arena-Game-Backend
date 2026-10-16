package com.arenacity.domain.usecase.athlete

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.*
import com.arenacity.domain.repository.AthleteRepository
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.repository.PackRepository
import com.arenacity.domain.rules.AthleteFactory
import com.arenacity.domain.rules.DailyOffer
import com.arenacity.domain.rules.DailyOffers
import com.arenacity.domain.rules.PackSpec
import com.arenacity.domain.rules.Packs
import com.arenacity.domain.rules.PullTier
import com.arenacity.domain.usecase.club.ClubService
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

/** A pack as the shop shows it: the spec, whether the club level has opened it, and whether today's free one is still there. */
data class PackOffer(val spec: PackSpec, val open: Boolean, val freeToday: Boolean)
data class PackShelf(val offers: List<PackOffer>, val sinceEpic: Int, val sinceLegend: Int, val collection: Int)
data class PackResult(val open: PackOpen, val athletes: List<Athlete>, val club: Club)
/** The day's pack offer as a club sees it: whether its level has opened the pack and whether it was bought today. */
data class DailyOfferView(val offer: DailyOffer, val open: Boolean, val bought: Boolean)

/**
 * The pack shop: what is on the shelf and the opening itself (plan §3b).
 *
 * DEPRECATED (16 October 2026): heroes replace cards and packs (docs/claude/HEROES-INSTEAD-OF-CARDS.md in the mobile repo;
 * the server side: rules.Heroes, usecase.hero.HeroUseCases). Kept, unchanged, while the current client still opens packs.
 */
class PackUseCases(private val service: ClubService, private val clubs: ClubRepository, private val athletes: AthleteRepository, private val packs: PackRepository,
                   private val clock: Clock, private val tx: Tx = NoTx) {
    private fun today(club: Club) = clock.now().atZone(ZoneId.of(club.timezone)).toLocalDate()

    /** Cards pulled since the last epic and since the last legend, from the newest openings backwards. */
    private fun pity(history: List<PackOpen>): Pair<Int, Int> {
        var sinceEpic = 0; var sinceLegend = 0; var epicSeen = false; var legendSeen = false
        for (o in history) for (t in o.tiers.asReversed()) {
            if (!legendSeen) { if (t == PullTier.LEGEND) legendSeen = true else sinceLegend++ }
            if (!epicSeen) { if (t == PullTier.LEGEND || t == PullTier.EPIC) epicSeen = true else sinceEpic++ }
        }
        return sinceEpic to sinceLegend
    }
    private fun freeToday(club: Club, history: List<PackOpen>): Boolean {
        val day = today(club); val zone = ZoneId.of(club.timezone)
        return history.none { it.kind == CardKind.CLASSIC && it.priceCoins == 0L && it.priceGold == 0 && it.createdAt.atZone(zone).toLocalDate() == day }
    }

    suspend fun shelf(userId: UUID): PackShelf {
        val club = service.clubOf(userId)
        val history = packs.listByClub(club.id)
        val (e, l) = pity(history)
        val count = athletes.listByClub(club.id).count { it.retiredAt == null }
        return PackShelf(Packs.ALL.map { PackOffer(it, club.level >= it.unlockLevel, it.kind == CardKind.CLASSIC && freeToday(club, history)) }, e, l, count)
    }

    suspend fun open(userId: UUID, kind: CardKind): PackResult = tx {
        val spec = Packs.of(kind) ?: throw NotFoundException("No such pack")
        val club = service.lockedClubOf(userId)
        if (club.level < spec.unlockLevel) throw ValidationException("This pack opens at club level {0}", spec.unlockLevel)
        val history = packs.listByClub(club.id)
        val free = spec.kind == CardKind.CLASSIC && freeToday(club, history)
        pull(club, spec, if (free) 0L else spec.coins, if (free) 0 else spec.gold, history, clock.now())
    }

    /** The day's offer (rules.DailyOffers) for the club. */
    suspend fun offer(userId: UUID): DailyOfferView {
        val club = service.clubOf(userId); val now = clock.now()
        val o = DailyOffers.of(now, club.level)
        return DailyOfferView(o, club.level >= o.spec.unlockLevel, club.offerDay == o.day)
    }

    /** Buys the day's offer at its discounted price: once a UTC day per club. */
    suspend fun buyOffer(userId: UUID): Pair<PackResult, DailyOfferView> = tx {
        val club = service.lockedClubOf(userId); val now = clock.now()
        val o = DailyOffers.of(now, club.level)
        if (club.level < o.spec.unlockLevel) throw ValidationException("This pack opens at club level {0}", o.spec.unlockLevel)
        if (club.offerDay == o.day) throw ConflictException("Today's offer is already bought")
        val r = pull(club.copy(offerDay = o.day), o.spec, 0L, o.price, packs.listByClub(club.id), now)
        r to DailyOfferView(o, true, true)
    }

    /** One opening of [spec] for [coins] and [gold], the club saved with the price paid. */
    private suspend fun pull(club: Club, spec: PackSpec, coins: Long, gold: Int, history: List<PackOpen>, now: java.time.Instant): PackResult {
        val squad = athletes.listByClub(club.id)
        if (squad.count { it.retiredAt == null } + Packs.CARDS_PER_PACK > Packs.COLLECTION_MAX) throw ConflictException("The collection is full — release someone first")
        if (club.coins < coins) throw ValidationException("Not enough coins")
        if (club.gold < gold) throw ValidationException("Not enough gold")
        var (sinceEpic, sinceLegend) = pity(history)
        val r = Random(now.toEpochMilli() xor club.id.leastSignificantBits)
        val tiers = ArrayList<PullTier>(); val pulled = ArrayList<Athlete>()
        repeat(Packs.CARDS_PER_PACK) {
            val tier = Packs.roll(spec, r, sinceEpic, sinceLegend)
            tiers += tier
            if (tier == PullTier.LEGEND) { sinceLegend = 0; sinceEpic = 0 } else if (tier == PullTier.EPIC) { sinceEpic = 0; sinceLegend++ } else { sinceEpic++; sinceLegend++ }
            // TODO(peoples): a generated athlete has no people yet; with the roster's characters, draw only those
            // rules.Peoples.dropsInPacks allows (humans, and the peoples whose island the club opened: ClubService.openZones)
            pulled += AthleteFactory.create(r.nextLong(), club.id, Sport.FOOTBALL, tier.quality.first, tier.quality.last, null, now).copy(id = UUID.randomUUID(), cardKind = spec.kind)
        }
        athletes.createAll(pulled)
        val saved = clubs.update(club.copy(coins = club.coins - coins, gold = club.gold - gold, updatedAt = now))
        val open = packs.create(PackOpen(UUID.randomUUID(), club.id, spec.kind, coins, gold, tiers, pulled.map { it.id }, now))
        return PackResult(open, pulled, saved)
    }
}
