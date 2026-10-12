package com.arenacity.domain.repository

import com.arenacity.domain.model.*
import java.util.UUID

interface AthleteRepository {
    /** Current squad (retired / released athletes excluded). */
    suspend fun listByClub(clubId: UUID): List<Athlete>
    /** Every athlete name the clubs ever had, retired ones included (match highlights). */
    suspend fun namesByClubs(clubIds: Collection<UUID>): Map<UUID, String> = clubIds.flatMap { listByClub(it) }.associate { it.id to it.name }
    suspend fun findById(id: UUID): Athlete?
    suspend fun createAll(athletes: List<Athlete>)
    suspend fun update(athlete: Athlete): Athlete
}

interface TrainingRepository {
    suspend fun listByClub(clubId: UUID): List<Training>
    suspend fun findById(id: UUID): Training?
    suspend fun create(training: Training): Training
    /** @return true when this call removed the row (used to finalise a training exactly once). */
    suspend fun delete(id: UUID): Boolean
}

interface LineupRepository {
    suspend fun find(clubId: UUID, sport: Sport): Lineup?
    suspend fun upsert(lineup: Lineup): Lineup
}

interface PackRepository {
    /** The club's openings, newest first, at most [limit]. */
    suspend fun listByClub(clubId: UUID, limit: Int = 60): List<com.arenacity.domain.model.PackOpen>
    suspend fun create(open: com.arenacity.domain.model.PackOpen): com.arenacity.domain.model.PackOpen
}
