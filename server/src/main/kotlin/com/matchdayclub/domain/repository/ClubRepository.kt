package com.matchdayclub.domain.repository

import com.matchdayclub.domain.model.Building
import com.matchdayclub.domain.model.Club
import java.util.UUID

interface ClubRepository {
    suspend fun findByUserId(userId: UUID): Club?
    suspend fun findById(id: UUID): Club?
    suspend fun findByIds(ids: Collection<UUID>): List<Club>
    suspend fun listBots(tzBucket: Int): List<Club>
    suspend fun create(club: Club): Club
    suspend fun update(club: Club): Club
}

interface BuildingRepository {
    suspend fun listByClub(clubId: UUID): List<Building>
    suspend fun findById(id: UUID): Building?
    suspend fun create(building: Building): Building
    suspend fun update(building: Building): Building
    suspend fun delete(id: UUID)
}
