package com.arenacity.data.repository

import com.arenacity.common.extension.dbQuery
import com.arenacity.data.database.ArenaMatchTable
import com.arenacity.domain.model.ArenaMatch
import com.arenacity.domain.model.ArenaMatchStatus
import com.arenacity.domain.repository.ArenaMatchRepository
import org.jetbrains.exposed.sql.*
import java.util.UUID

class ArenaMatchRepositoryImpl : ArenaMatchRepository {
    override suspend fun create(match: ArenaMatch) = dbQuery {
        ArenaMatchTable.insert {
            it[id] = match.id; it[clubId] = match.clubId; it[stake] = match.stake; it[story] = match.story; it[status] = match.status.name
            it[startedAt] = match.startedAt; it[finishedAt] = match.finishedAt; it[won] = match.won
        }
        match
    }
    override suspend fun find(id: UUID) = dbQuery { ArenaMatchTable.selectAll().where { ArenaMatchTable.id eq id }.singleOrNull()?.toMatch() }
    override suspend fun findOpen(clubId: UUID) = dbQuery {
        ArenaMatchTable.selectAll().where { (ArenaMatchTable.clubId eq clubId) and (ArenaMatchTable.status eq ArenaMatchStatus.OPEN.name) }.map { it.toMatch() }
    }
    override suspend fun update(match: ArenaMatch) = dbQuery {
        ArenaMatchTable.update({ ArenaMatchTable.id eq match.id }) { it[status] = match.status.name; it[finishedAt] = match.finishedAt; it[won] = match.won }
        match
    }
    private fun ResultRow.toMatch() = ArenaMatch(this[ArenaMatchTable.id], this[ArenaMatchTable.clubId], this[ArenaMatchTable.stake], this[ArenaMatchTable.story],
        ArenaMatchStatus.valueOf(this[ArenaMatchTable.status]), this[ArenaMatchTable.startedAt], this[ArenaMatchTable.finishedAt], this[ArenaMatchTable.won])
}
