package com.matchdayclub.data.repository

import com.matchdayclub.common.extension.dbQuery
import com.matchdayclub.data.database.AthleteTable
import com.matchdayclub.data.database.PackOpenTable
import com.matchdayclub.data.database.LineupTable
import com.matchdayclub.data.database.TrainingTable
import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.repository.AthleteRepository
import com.matchdayclub.domain.repository.PackRepository
import com.matchdayclub.domain.repository.LineupRepository
import com.matchdayclub.domain.repository.TrainingRepository
import com.matchdayclub.engine.model.Position
import com.matchdayclub.engine.model.Tactics
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import java.util.UUID

class AthleteRepositoryImpl : AthleteRepository {
    override suspend fun listByClub(clubId: UUID) = dbQuery { AthleteTable.selectAll().where { (AthleteTable.clubId eq clubId) and AthleteTable.retiredAt.isNull() }.orderBy(AthleteTable.createdAt).map { it.toAthlete() } }
    override suspend fun namesByClubs(clubIds: Collection<UUID>): Map<UUID, String> = if (clubIds.isEmpty()) emptyMap() else dbQuery {
        AthleteTable.select(AthleteTable.id, AthleteTable.name).where { AthleteTable.clubId inList clubIds }.associate { it[AthleteTable.id] to it[AthleteTable.name] }
    }
    override suspend fun findById(id: UUID) = dbQuery { AthleteTable.selectAll().where { AthleteTable.id eq id }.singleOrNull()?.toAthlete() }
    override suspend fun createAll(athletes: List<Athlete>) {
        dbQuery { AthleteTable.batchInsert(athletes) { a -> fill(a); this[AthleteTable.id] = a.id; this[AthleteTable.clubId] = a.clubId; this[AthleteTable.sport] = a.sport.name; this[AthleteTable.position] = a.position.name; this[AthleteTable.rarity] = a.rarity.name; this[AthleteTable.createdAt] = a.createdAt } }
    }
    override suspend fun update(athlete: Athlete) = dbQuery { AthleteTable.update({ AthleteTable.id eq athlete.id }) { it.fill(athlete) }; athlete }

    private fun UpdateBuilder<*>.fill(a: Athlete) {
        this[AthleteTable.name] = a.name; this[AthleteTable.attack] = a.attack; this[AthleteTable.defense] = a.defense; this[AthleteTable.physical] = a.physical
        this[AthleteTable.technique] = a.technique; this[AthleteTable.mental] = a.mental; this[AthleteTable.fatigue] = a.fatigue; this[AthleteTable.fatigueAt] = a.fatigueAt
        this[AthleteTable.injuredUntil] = a.injuredUntil; this[AthleteTable.morale] = a.morale; this[AthleteTable.updatedAt] = a.updatedAt
        this[AthleteTable.age] = a.age; this[AthleteTable.potential] = a.potential; this[AthleteTable.retiredAt] = a.retiredAt; this[AthleteTable.xp] = a.xp
        this[AthleteTable.techniques] = TechniqueCodec.encode(a.techniques); this[AthleteTable.skillPoints] = a.skillPoints; this[AthleteTable.xpTotal] = a.xpTotal
        this[AthleteTable.talent] = a.talent; this[AthleteTable.cardKind] = a.cardKind.name
    }

    private fun ResultRow.toAthlete() = Athlete(
        this[AthleteTable.id], this[AthleteTable.clubId], this[AthleteTable.name], Sport.valueOf(this[AthleteTable.sport]), Position.valueOf(this[AthleteTable.position]),
        this[AthleteTable.attack], this[AthleteTable.defense], this[AthleteTable.physical], this[AthleteTable.technique], this[AthleteTable.mental],
        this[AthleteTable.age], Rarity.valueOf(this[AthleteTable.rarity]), this[AthleteTable.fatigue], this[AthleteTable.fatigueAt], this[AthleteTable.injuredUntil], this[AthleteTable.morale],
        this[AthleteTable.createdAt], this[AthleteTable.updatedAt], this[AthleteTable.potential], this[AthleteTable.retiredAt], this[AthleteTable.xp],
        TechniqueCodec.decode(this[AthleteTable.techniques]), this[AthleteTable.skillPoints], this[AthleteTable.xpTotal], this[AthleteTable.talent],
        CardKind.valueOf(this[AthleteTable.cardKind]),
    )
}

/** Technique levels in one column: "SHOOT:2,FEINT:1". */
object TechniqueCodec {
    fun encode(t: Map<String, Int>): String = t.entries.filter { it.value > 0 }.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }
    fun decode(s: String?): Map<String, Int> = if (s.isNullOrBlank()) emptyMap() else s.split(",").mapNotNull { e -> val i = e.indexOf(':'); if (i <= 0) null else e.substring(0, i) to (e.substring(i + 1).toIntOrNull() ?: 0) }.filter { it.second > 0 }.toMap()
}

class TrainingRepositoryImpl : TrainingRepository {
    override suspend fun listByClub(clubId: UUID) = dbQuery { TrainingTable.selectAll().where { TrainingTable.clubId eq clubId }.map { it.toTraining() } }
    override suspend fun findById(id: UUID) = dbQuery { TrainingTable.selectAll().where { TrainingTable.id eq id }.singleOrNull()?.toTraining() }
    override suspend fun create(training: Training) = dbQuery {
        TrainingTable.insert { it[id] = training.id; it[clubId] = training.clubId; it[athleteId] = training.athleteId; it[attribute] = training.kindKey; it[delta] = training.delta; it[endsAt] = training.endsAt; it[createdAt] = training.createdAt }
        training
    }
    override suspend fun delete(id: UUID): Boolean = dbQuery { TrainingTable.deleteWhere { TrainingTable.id eq id } > 0 }
    private fun ResultRow.toTraining(): Training {
        val kind = this[TrainingTable.attribute]; val drill = kind == "SKILL"
        return Training(this[TrainingTable.id], this[TrainingTable.clubId], this[TrainingTable.athleteId], if (drill) AttributeKind.TECHNIQUE else AttributeKind.valueOf(kind), this[TrainingTable.delta], this[TrainingTable.endsAt], this[TrainingTable.createdAt], drill)
    }
}

class LineupRepositoryImpl : LineupRepository {
    private val json = Json
    override suspend fun find(clubId: UUID, sport: Sport) = dbQuery {
        LineupTable.selectAll().where { (LineupTable.clubId eq clubId) and (LineupTable.sport eq sport.name) }.singleOrNull()?.let {
            Lineup(clubId, sport, json.decodeFromString<List<String>>(it[LineupTable.startersJson]).map(UUID::fromString), json.decodeFromString<List<String>>(it[LineupTable.benchJson]).map(UUID::fromString), json.decodeFromString<Tactics>(it[LineupTable.tacticsJson]), it[LineupTable.updatedAt])
        }
    }
    override suspend fun upsert(lineup: Lineup) = dbQuery {
        LineupTable.upsert {
            it[clubId] = lineup.clubId; it[sport] = lineup.sport.name; it[startersJson] = json.encodeToString(lineup.starters.map(UUID::toString))
            it[benchJson] = json.encodeToString(lineup.bench.map(UUID::toString)); it[tacticsJson] = json.encodeToString(lineup.tactics); it[updatedAt] = lineup.updatedAt
        }
        lineup
    }
}

class PackRepositoryImpl : PackRepository {
    override suspend fun listByClub(clubId: UUID, limit: Int) = dbQuery {
        PackOpenTable.selectAll().where { PackOpenTable.clubId eq clubId }.orderBy(PackOpenTable.createdAt, SortOrder.DESC).limit(limit).map { it.toOpen() }
    }
    override suspend fun create(open: PackOpen) = dbQuery {
        PackOpenTable.insert {
            it[id] = open.id; it[clubId] = open.clubId; it[kind] = open.kind.name; it[priceCoins] = open.priceCoins; it[priceGold] = open.priceGold
            it[tiers] = open.tiers.joinToString(",") { t -> t.name }; it[athleteIds] = open.athleteIds.joinToString(","); it[createdAt] = open.createdAt
        }
        open
    }
    private fun ResultRow.toOpen() = PackOpen(
        this[PackOpenTable.id], this[PackOpenTable.clubId], CardKind.valueOf(this[PackOpenTable.kind]), this[PackOpenTable.priceCoins], this[PackOpenTable.priceGold],
        this[PackOpenTable.tiers].split(",").filter { it.isNotBlank() }.map { com.matchdayclub.domain.rules.PullTier.valueOf(it) },
        this[PackOpenTable.athleteIds].split(",").filter { it.isNotBlank() }.map { UUID.fromString(it) }, this[PackOpenTable.createdAt],
    )
}
