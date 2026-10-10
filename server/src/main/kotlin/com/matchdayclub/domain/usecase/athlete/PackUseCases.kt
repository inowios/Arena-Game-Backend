package com.matchdayclub.domain.usecase.athlete

import com.matchdayclub.common.time.Clock
import com.matchdayclub.common.tx.NoTx
import com.matchdayclub.common.tx.Tx
import com.matchdayclub.domain.exception.ConflictException
import com.matchdayclub.domain.exception.NotFoundException
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.repository.AthleteRepository
import com.matchdayclub.domain.repository.ClubRepository
import com.matchdayclub.domain.repository.PackRepository
import com.matchdayclub.domain.rules.AthleteFactory
import com.matchdayclub.domain.rules.PackSpec
import com.matchdayclub.domain.rules.Packs
import com.matchdayclub.domain.rules.PullTier
import com.matchdayclub.domain.usecase.club.ClubService
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

/** A pack as the shop shows it: the spec, whether the club level has opened it, and whether today's free one is still there. */
data class PackOffer(val spec: PackSpec, val open: Boolean, val freeToday: Boolean)
data class PackShelf(val offers: List<PackOffer>, val sinceEpic: Int, val sinceLegend: Int, val collection: Int)
data class PackResult(val open: PackOpen, val athletes: List<Athlete>, val club: Club)

/** The pack shop: what is on the shelf and the opening itself (plan §3b). */
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
        val squad = athletes.listByClub(club.id)
        if (squad.count { it.retiredAt == null } + Packs.CARDS_PER_PACK > Packs.COLLECTION_MAX) throw ConflictException("The collection is full — release someone first")
        val free = spec.kind == CardKind.CLASSIC && freeToday(club, history)
        val coins = if (free) 0L else spec.coins; val gold = if (free) 0 else spec.gold
        if (club.coins < coins) throw ValidationException("Not enough coins")
        if (club.gold < gold) throw ValidationException("Not enough gold")
        val now = clock.now()
        var (sinceEpic, sinceLegend) = pity(history)
        val r = Random(now.toEpochMilli() xor club.id.leastSignificantBits)
        val tiers = ArrayList<PullTier>(); val pulled = ArrayList<Athlete>()
        repeat(Packs.CARDS_PER_PACK) {
            val tier = Packs.roll(spec, r, sinceEpic, sinceLegend)
            tiers += tier
            if (tier == PullTier.LEGEND) { sinceLegend = 0; sinceEpic = 0 } else if (tier == PullTier.EPIC) { sinceEpic = 0; sinceLegend++ } else { sinceEpic++; sinceLegend++ }
            pulled += AthleteFactory.create(r.nextLong(), club.id, Sport.FOOTBALL, tier.quality.first, tier.quality.last, null, now).copy(id = UUID.randomUUID(), cardKind = spec.kind)
        }
        athletes.createAll(pulled)
        val saved = clubs.update(club.copy(coins = club.coins - coins, gold = club.gold - gold, updatedAt = now))
        val open = packs.create(PackOpen(UUID.randomUUID(), club.id, spec.kind, coins, gold, tiers, pulled.map { it.id }, now))
        PackResult(open, pulled, saved)
    }
}
