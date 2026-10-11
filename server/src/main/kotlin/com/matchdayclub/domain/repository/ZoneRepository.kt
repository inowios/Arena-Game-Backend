package com.matchdayclub.domain.repository

import com.matchdayclub.domain.model.ClubZone
import java.util.UUID

interface ZoneRepository {
    suspend fun listByClub(clubId: UUID): List<ClubZone>
    suspend fun upsert(zone: ClubZone): ClubZone
}
