package com.matchdayclub.testutil

import com.matchdayclub.domain.model.Building
import com.matchdayclub.domain.model.Club
import com.matchdayclub.domain.repository.BuildingRepository
import com.matchdayclub.domain.repository.ClubRepository
import java.util.UUID

class FakeClubRepository : ClubRepository {
    val rows = mutableMapOf<UUID, Club>()
    override suspend fun findByUserId(userId: UUID) = rows.values.firstOrNull { it.userId == userId }
    override suspend fun findById(id: UUID) = rows[id]
    override suspend fun findByIds(ids: Collection<UUID>) = ids.mapNotNull { rows[it] }
    override suspend fun listBots(tzBucket: Int) = rows.values.filter { it.isBot && it.tzBucket == tzBucket }
    override suspend fun create(club: Club): Club { rows[club.id] = club; return club }
    override suspend fun update(club: Club): Club { rows[club.id] = club; return club }
}

class FakeBuildingRepository : BuildingRepository {
    val rows = linkedMapOf<UUID, Building>()
    override suspend fun listByClub(clubId: UUID) = rows.values.filter { it.clubId == clubId }
    override suspend fun findById(id: UUID) = rows[id]
    override suspend fun create(building: Building): Building { rows[building.id] = building; return building }
    override suspend fun update(building: Building): Building { rows[building.id] = building; return building }
    override suspend fun delete(id: UUID) { rows.remove(id) }
}
