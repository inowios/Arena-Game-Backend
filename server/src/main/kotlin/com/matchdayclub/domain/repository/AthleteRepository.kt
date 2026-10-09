package com.matchdayclub.domain.repository

import com.matchdayclub.domain.model.*
import java.util.UUID

interface AthleteRepository {
    suspend fun listByClub(clubId: UUID): List<Athlete>
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
