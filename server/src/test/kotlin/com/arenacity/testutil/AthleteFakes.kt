package com.arenacity.testutil

import com.arenacity.domain.model.*
import com.arenacity.domain.repository.*
import java.util.UUID

class FakeAthleteRepository : AthleteRepository {
    val rows = linkedMapOf<UUID, Athlete>()
    override suspend fun listByClub(clubId: UUID) = rows.values.filter { it.clubId == clubId && it.retiredAt == null }
    override suspend fun namesByClubs(clubIds: Collection<UUID>) = rows.values.filter { it.clubId in clubIds }.associate { it.id to it.name }
    override suspend fun findById(id: UUID) = rows[id]
    override suspend fun createAll(athletes: List<Athlete>) { athletes.forEach { rows[it.id] = it } }
    override suspend fun update(athlete: Athlete): Athlete { rows[athlete.id] = athlete; return athlete }
}

class FakeTrainingRepository : TrainingRepository {
    val rows = linkedMapOf<UUID, Training>()
    override suspend fun listByClub(clubId: UUID) = rows.values.filter { it.clubId == clubId }
    override suspend fun findById(id: UUID) = rows[id]
    override suspend fun create(training: Training): Training { rows[training.id] = training; return training }
    override suspend fun delete(id: UUID): Boolean = rows.remove(id) != null
}

class FakeLineupRepository : LineupRepository {
    val rows = mutableMapOf<Pair<UUID, Sport>, Lineup>()
    override suspend fun find(clubId: UUID, sport: Sport) = rows[clubId to sport]
    override suspend fun upsert(lineup: Lineup): Lineup { rows[lineup.clubId to lineup.sport] = lineup; return lineup }
}
