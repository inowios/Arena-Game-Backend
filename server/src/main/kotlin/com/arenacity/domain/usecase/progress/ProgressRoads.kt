package com.arenacity.domain.usecase.progress

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.Club
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.economy.CosmeticsService
import java.util.UUID

/** Claimed road steps per club. */
interface RoadRepository {
    suspend fun claimed(clubId: UUID): Map<Road, Set<Int>>
    /** Records the claim; false when it was already claimed (concurrent taps pay once). */
    suspend fun claim(clubId: UUID, road: Road, index: Int, at: java.time.Instant): Boolean
}

data class RoadStepState(val step: RoadStep, val reached: Boolean, val claimed: Boolean)
data class RoadView(val road: Road, val progress: Int, val steps: List<RoadStepState>)
data class RoadClaim(val coins: Long, val gold: Int, val item: String?, val club: Club, val view: RoadView)

class RoadUseCases(
    private val service: ClubService, private val clubs: ClubRepository, private val roads: RoadRepository, private val cosmetics: CosmeticsService,
    private val clock: Clock, private val tx: Tx = NoTx,
) {
    suspend fun list(userId: UUID): List<RoadView> { val club = service.clubOf(userId); val claimed = roads.claimed(club.id); return Road.values().map { view(club, it, claimed[it] ?: emptySet()) } }

    private fun view(club: Club, road: Road, claimed: Set<Int>): RoadView {
        val p = Roads.progress(road, club)
        return RoadView(road, p, Roads.of(road).map { RoadStepState(it, p >= it.at, it.index in claimed) })
    }

    suspend fun claim(userId: UUID, road: Road, index: Int): RoadClaim = tx {
        val club = service.lockedClubOf(userId)
        val step = Roads.step(road, index) ?: throw NotFoundException("Unknown reward")
        if (Roads.progress(road, club) < step.at) throw ValidationException("Not reached yet")
        val now = clock.now()
        if (!roads.claim(club.id, road, index, now)) throw ConflictException("Already claimed")
        val r = step.reward
        var gold = r.gold
        var item: String? = null
        if (r.item != null) {
            val c = Cosmetics.find(r.item) ?: throw NotFoundException("Unknown item")
            if (cosmetics.grant(club.id, c, CosmeticSource.ROAD)) item = c.id else gold += Cosmetics.DUPLICATE_GOLD
        }
        val saved = clubs.update(club.copy(coins = club.coins + r.coins, gold = club.gold + gold, updatedAt = now))
        RoadClaim(r.coins, gold, item, saved, view(saved, road, roads.claimed(club.id)[road] ?: emptySet()))
    }
}

data class CollectionState(val set: CollectionSet, val owned: List<String>, val complete: Boolean)

class CollectionsUseCases(private val service: ClubService, private val cosmetics: CosmeticsService) {
    suspend fun list(userId: UUID): Pair<List<CollectionState>, Int> {
        val owned = cosmetics.owned(service.clubOf(userId).id)
        return Collections.ALL.map { s -> CollectionState(s, s.items.filter { it in owned }, s.items.all { it in owned }) } to Collections.incomePct(owned)
    }
    suspend fun incomePct(clubId: UUID) = Collections.incomePct(cosmetics.owned(clubId))
}
