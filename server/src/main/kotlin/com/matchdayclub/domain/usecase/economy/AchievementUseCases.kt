package com.matchdayclub.domain.usecase.economy

import com.matchdayclub.common.time.Clock
import com.matchdayclub.common.tx.NoTx
import com.matchdayclub.common.tx.Tx
import com.matchdayclub.domain.exception.ConflictException
import com.matchdayclub.domain.exception.NotFoundException
import com.matchdayclub.domain.model.BuildingState
import com.matchdayclub.domain.model.Club
import com.matchdayclub.domain.model.TrophyKind
import com.matchdayclub.domain.repository.AchievementRepository
import com.matchdayclub.domain.repository.BuildingRepository
import com.matchdayclub.domain.repository.ClubRepository
import com.matchdayclub.domain.repository.SeasonRepository
import com.matchdayclub.domain.rules.*
import com.matchdayclub.domain.usecase.athlete.SectionService
import com.matchdayclub.domain.usecase.club.ClubService
import java.util.UUID

data class AchievementState(val achievement: Achievement, val value: Long, val claimedTier: Int) {
    val reachedTier get() = achievement.reached(value)
    val claimable get() = reachedTier > claimedTier
}

data class AchievementClaim(val gold: Int, val items: List<String>, val list: List<AchievementState>, val club: Club)

/** Current metric values: lifetime counters plus values read from the club's state. */
class AchievementService(
    private val repo: AchievementRepository,
    private val buildings: BuildingRepository,
    private val seasons: SeasonRepository,
    private val sections: SectionService? = null,
) {
    suspend fun values(club: Club): Map<Metric, Long> {
        val counters = repo.counters(club.id)
        val trophies = seasons.trophiesOf(club.id)
        val list = buildings.listByClub(club.id)
        return Metric.values().associateWith { m ->
            when (m) {
                Metric.CLUB_LEVEL -> club.level.toLong()
                Metric.DIVISION -> club.division.toLong()
                Metric.TITLES -> trophies.count { it.kind == TrophyKind.LEAGUE_TITLE }.toLong()
                Metric.PROMOTIONS -> trophies.count { it.kind == TrophyKind.PROMOTION }.toLong()
                Metric.BUILDINGS_MAXED -> list.count { it.type != BuildingType.DECOR && it.state == BuildingState.IDLE && it.level >= BuildingCatalog.spec(it.type).maxLevel }.toLong()
                Metric.SECTIONS_OPEN -> (sections?.active(club, clubBuildings = list)?.size ?: 1).toLong()
                else -> counters[m] ?: 0L
            }
        }
    }

    suspend fun list(club: Club): List<AchievementState> {
        val values = values(club)
        val claimed = repo.claimed(club.id)
        return Achievements.ALL.map { AchievementState(it, values[it.metric] ?: 0L, claimed[it.id] ?: 0) }
    }
}

class AchievementUseCases(
    private val service: ClubService,
    private val clubs: ClubRepository,
    private val repo: AchievementRepository,
    private val achievements: AchievementService,
    private val cosmetics: CosmeticsService,
    private val clock: Clock,
    private val tx: Tx = NoTx,
) {
    suspend fun list(userId: UUID): List<AchievementState> = achievements.list(service.clubOf(userId))

    /** Pays every reached, unclaimed tier of one achievement: gold, plus the achievement's cosmetic on some tiers. */
    suspend fun claim(userId: UUID, achievementId: String): AchievementClaim = tx {
        val a = Achievements.find(achievementId) ?: throw NotFoundException("Unknown achievement")
        val club = service.lockedClubOf(userId)
        val state = achievements.list(club).first { it.achievement.id == a.id }
        if (!state.claimable) throw ConflictException("Nothing to claim yet")
        var gold = 0
        val items = mutableListOf<String>()
        for (tier in state.claimedTier + 1..state.reachedTier) {
            gold += a.gold[tier - 1]
            Achievements.item(a.id, tier)?.let { item -> if (cosmetics.grant(club.id, item, CosmeticSource.ACHIEVEMENT)) items += item.id else gold += Cosmetics.DUPLICATE_GOLD }
        }
        val now = clock.now()
        repo.setClaimed(club.id, a.id, state.reachedTier, now)
        val updated = clubs.update(club.copy(gold = club.gold + gold, updatedAt = now))
        AchievementClaim(gold, items, achievements.list(updated), updated)
    }
}
