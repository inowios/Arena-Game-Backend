package com.matchdayclub.domain.usecase.progress

import com.matchdayclub.common.time.Clock
import com.matchdayclub.common.tx.NoTx
import com.matchdayclub.common.tx.Tx
import com.matchdayclub.domain.exception.ConflictException
import com.matchdayclub.domain.exception.NotFoundException
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.Athlete
import com.matchdayclub.domain.model.BuildingState
import com.matchdayclub.domain.model.Club
import com.matchdayclub.domain.model.Sport
import com.matchdayclub.domain.repository.AthleteRepository
import com.matchdayclub.domain.repository.BuildingRepository
import com.matchdayclub.domain.repository.ClubRepository
import com.matchdayclub.domain.rules.*
import com.matchdayclub.domain.usecase.club.ClubService
import com.matchdayclub.domain.usecase.economy.AchievementService
import com.matchdayclub.domain.usecase.economy.CosmeticsService
import java.util.UUID

/** Claimed road steps per club. */
interface RoadRepository {
    suspend fun claimed(clubId: UUID): Map<Road, Set<Int>>
    /** Records the claim; false when it was already claimed (concurrent taps pay once). */
    suspend fun claim(clubId: UUID, road: Road, index: Int, at: java.time.Instant): Boolean
}

data class RoadStepState(val step: RoadStep, val reached: Boolean, val claimed: Boolean)
data class RoadView(val road: Road, val progress: Int, val steps: List<RoadStepState>)
data class RoadClaim(val coins: Long, val gold: Int, val item: String?, val athlete: Athlete?, val club: Club, val view: RoadView)

class RoadUseCases(
    private val service: ClubService, private val clubs: ClubRepository, private val roads: RoadRepository, private val cosmetics: CosmeticsService,
    private val athletes: AthleteRepository, private val clock: Clock, private val tx: Tx = NoTx,
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
        var athlete: Athlete? = null
        if (r.pack) {
            val squad = athletes.listByClub(club.id).count { it.sport == Sport.FOOTBALL && it.retiredAt == null }
            if (squad < Careers.squadMax(Sport.FOOTBALL)) {
                val q = Arenas.packQuality(Arenas.of(club.trophies))
                athlete = AthleteFactory.create(now.toEpochMilli() xor club.id.mostSignificantBits, club.id, Sport.FOOTBALL, q.first, q.last, null, now)
                athletes.createAll(listOf(athlete))
            } else gold += Roads.PACK_FALLBACK_GOLD
        }
        val saved = clubs.update(club.copy(coins = club.coins + r.coins, gold = club.gold + gold, updatedAt = now))
        RoadClaim(r.coins, gold, item, athlete, saved, view(saved, road, roads.claimed(club.id)[road] ?: emptySet()))
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

/** One honour on the museum's shelves: an achievement tier ("champion", 2), or the arena reached ("arena", 5). */
data class Exhibit(val id: String, val tier: Int, val value: Long)
data class MuseumView(val level: Int, val capacity: Int, val exhibits: List<Exhibit>, val total: Int)

/** The museum of a club (own or visited): the honours that fit its shelves, the rarest first. */
class MuseumService(private val clubs: ClubRepository, private val buildings: BuildingRepository, private val achievements: AchievementService) {
    private val order = listOf("champion", "cup_winner", "arena", "climber", "promoted", "stadium", "winner", "striker", "showman", "master_builder", "growing_club",
        "all_rounder", "multisport", "talent_spotter", "coach", "tycoon", "clearer", "loyal", "friendly_face", "social")

    suspend fun of(clubId: UUID): MuseumView {
        val club = clubs.findById(clubId) ?: throw NotFoundException("Club not found")
        val museum = buildings.listByClub(club.id).firstOrNull { it.type == BuildingType.MUSEUM }
        val level = museum?.let { if (it.state == BuildingState.UPGRADING) it.level - 1 else it.level } ?: 0
        val values = achievements.values(club)
        val all = Achievements.ALL.sortedBy { order.indexOf(it.id).let { i -> if (i < 0) 99 else i } }.flatMap { a ->
            val v = values[a.metric] ?: 0L
            (1..a.reached(v)).reversed().map { t -> Exhibit(a.id, t, v) }
        }
        val cap = MuseumRules.capacity(level)
        return MuseumView(level, cap, all.take(cap), all.size)
    }
}
