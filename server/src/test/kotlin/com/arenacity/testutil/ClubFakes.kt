package com.arenacity.testutil

import com.arenacity.domain.model.Building
import com.arenacity.domain.model.Club
import com.arenacity.domain.repository.BuildingRepository
import com.arenacity.domain.repository.ClubRepository
import java.util.UUID

class FakeClubRepository : ClubRepository {
    val rows = mutableMapOf<UUID, Club>()
    override suspend fun findByUserId(userId: UUID) = rows.values.firstOrNull { it.userId == userId }
    override suspend fun findById(id: UUID) = rows[id]
    override suspend fun findByIds(ids: Collection<UUID>) = ids.mapNotNull { rows[it] }
    override suspend fun listBots(tzBucket: Int) = rows.values.filter { it.isBot && it.tzBucket == tzBucket }
    override suspend fun listNear(level: Int, spread: Int, except: UUID, limit: Int) = rows.values.filter { it.id != except && it.level in (level - spread)..(level + spread) }.take(limit)
    override suspend fun lockByUserId(userId: UUID) = findByUserId(userId)
    override suspend fun lockById(id: UUID) = findById(id)
    override suspend fun create(club: Club): Club { rows[club.id] = club; return club }
    override suspend fun update(club: Club): Club { rows[club.id] = club; return club }
}

class FakeBuildingRepository : BuildingRepository {
    val rows = linkedMapOf<UUID, Building>()
    override suspend fun listByClub(clubId: UUID) = rows.values.filter { it.clubId == clubId }
    override suspend fun findById(id: UUID) = rows[id]
    override suspend fun create(building: Building): Building { rows[building.id] = building; return building }
    override suspend fun update(building: Building): Building { rows[building.id] = building; return building }
    override suspend fun completeConstruction(building: Building): Boolean {
        val cur = rows[building.id] ?: return false
        if (cur.state != com.arenacity.domain.model.BuildingState.UPGRADING) return false
        rows[building.id] = building; return true
    }
    override suspend fun move(building: Building): Building { rows[building.id] = building; return building }
    override suspend fun delete(id: UUID) { rows.remove(id) }
    /** Tests that need buildings a bigger stadium opens: puts the club's stadium on [level] at once. */
    fun setStadium(clubId: UUID, level: Int) {
        val p = rows.values.first { it.clubId == clubId && it.type == com.arenacity.domain.rules.BuildingType.PITCH }
        rows[p.id] = p.copy(level = level, state = com.arenacity.domain.model.BuildingState.IDLE, upgradeEndsAt = null)
    }
}

class FakeMarketRepository : com.arenacity.domain.repository.MarketRepository {
    val rows = mutableSetOf<String>()
    override suspend fun purchased(clubId: UUID, period: Long, salt: Int) = rows.filter { it.startsWith("$clubId:$period:$salt:") }.map { it.substringAfterLast(':').toInt() }.toSet()
    override suspend fun markPurchased(clubId: UUID, period: Long, salt: Int, idx: Int, athleteId: UUID, at: java.time.Instant) = rows.add("$clubId:$period:$salt:$idx")
}

class FakeObstacleRepository : com.arenacity.domain.repository.ObstacleRepository {
    val rows = linkedMapOf<UUID, com.arenacity.domain.model.Obstacle>()
    val clocks = mutableMapOf<UUID, java.time.Instant?>()
    override suspend fun listByClub(clubId: UUID) = rows.values.filter { it.clubId == clubId }
    override suspend fun create(o: com.arenacity.domain.model.Obstacle) = o.also { rows[o.id] = it }
    override suspend fun update(o: com.arenacity.domain.model.Obstacle) = o.also { rows[o.id] = it }
    override suspend fun finishClearing(o: com.arenacity.domain.model.Obstacle): Boolean {
        val cur = rows[o.id] ?: return false
        if (cur.state != com.arenacity.domain.model.ObstacleState.CLEARING) return false
        rows[o.id] = o; return true
    }
    override suspend fun take(id: UUID): Boolean = rows[id]?.takeIf { it.state == com.arenacity.domain.model.ObstacleState.READY }?.let { rows.remove(id); true } ?: false
    override suspend fun spawnClock(clubId: UUID) = clocks[clubId]
    override suspend fun claimSpawn(clubId: UUID, expected: java.time.Instant?, next: java.time.Instant): Boolean {
        if (clocks[clubId] != expected) return false
        clocks[clubId] = next; return true
    }
}
