package com.arenacity.data.repository

import com.arenacity.common.extension.dbQuery
import com.arenacity.data.database.ClubTable
import com.arenacity.data.database.LiveMatchTable
import com.arenacity.domain.model.Club
import com.arenacity.domain.model.LiveMatch
import com.arenacity.domain.model.LiveStatus
import com.arenacity.domain.repository.LiveMatchRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import java.util.UUID

class LiveMatchRepositoryImpl : LiveMatchRepository {
    private val json = Json

    override suspend fun create(m: LiveMatch) = dbQuery {
        LiveMatchTable.insert {
            it[id] = m.id; it[clubId] = m.clubId; it[opponentId] = m.opponentId; it[seed] = m.seed; it[startersJson] = json.encodeToString(m.starters.map(UUID::toString)); it[oppStartersJson] = json.encodeToString(m.oppStarters.map(UUID::toString))
            it[tempo] = m.tempo; it[myOverall] = m.myOverall; it[oppOverall] = m.oppOverall; it[createdAt] = m.createdAt; it.fill(m)
        }
        m
    }

    override suspend fun find(id: UUID) = dbQuery { LiveMatchTable.selectAll().where { LiveMatchTable.id eq id }.singleOrNull()?.toModel() }
    override suspend fun findOpen(clubId: UUID) = dbQuery { LiveMatchTable.selectAll().where { (LiveMatchTable.clubId eq clubId) and (LiveMatchTable.status eq LiveStatus.OPEN.name) }.map { it.toModel() } }
    override suspend fun update(m: LiveMatch) = dbQuery { LiveMatchTable.update({ LiveMatchTable.id eq m.id }) { it.fill(m) }; m }
    override suspend fun opponents(clubId: UUID, minTrophies: Int, maxTrophies: Int, limit: Int): List<Club> = dbQuery {
        ClubTable.selectAll().where { (ClubTable.isBot eq false) and (ClubTable.id neq clubId) and (ClubTable.trophies greaterEq minTrophies) and (ClubTable.trophies lessEq maxTrophies) }
            .orderBy(ClubTable.updatedAt, SortOrder.DESC).limit(limit).map { it.toClub() }
    }
    override suspend fun recent(clubId: UUID, limit: Int) = dbQuery {
        LiveMatchTable.selectAll().where { (LiveMatchTable.clubId eq clubId) and (LiveMatchTable.status neq LiveStatus.OPEN.name) and (LiveMatchTable.status neq LiveStatus.CANCELLED.name) }.orderBy(LiveMatchTable.createdAt, SortOrder.DESC).limit(limit).map { it.toModel() }
    }

    private fun UpdateBuilder<*>.fill(m: LiveMatch) {
        this[LiveMatchTable.status] = m.status.name; this[LiveMatchTable.homeScore] = m.homeScore; this[LiveMatchTable.awayScore] = m.awayScore
        this[LiveMatchTable.resultJson] = m.resultJson; this[LiveMatchTable.trophiesDelta] = m.trophiesDelta; this[LiveMatchTable.finishedAt] = m.finishedAt
        this[LiveMatchTable.updatedAt] = m.finishedAt ?: m.createdAt
    }

    private fun ResultRow.toModel() = LiveMatch(
        this[LiveMatchTable.id], this[LiveMatchTable.clubId], this[LiveMatchTable.opponentId], this[LiveMatchTable.seed], LiveStatus.valueOf(this[LiveMatchTable.status]),
        json.decodeFromString<List<String>>(this[LiveMatchTable.startersJson]).map(UUID::fromString), json.decodeFromString<List<String>>(this[LiveMatchTable.oppStartersJson]).map(UUID::fromString),
        this[LiveMatchTable.tempo], this[LiveMatchTable.myOverall], this[LiveMatchTable.oppOverall], this[LiveMatchTable.createdAt],
        this[LiveMatchTable.homeScore], this[LiveMatchTable.awayScore], this[LiveMatchTable.resultJson], this[LiveMatchTable.trophiesDelta], this[LiveMatchTable.finishedAt],
    )
}
