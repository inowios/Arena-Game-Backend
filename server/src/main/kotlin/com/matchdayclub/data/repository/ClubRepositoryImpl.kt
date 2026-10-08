package com.matchdayclub.data.repository

import com.matchdayclub.common.extension.dbQuery
import com.matchdayclub.data.database.BuildingTable
import com.matchdayclub.data.database.ClubTable
import com.matchdayclub.domain.model.Building
import com.matchdayclub.domain.model.BuildingState
import com.matchdayclub.domain.model.Club
import com.matchdayclub.domain.repository.BuildingRepository
import com.matchdayclub.domain.repository.ClubRepository
import com.matchdayclub.domain.rules.BuildingType
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import java.util.UUID

class ClubRepositoryImpl : ClubRepository {
    override suspend fun findByUserId(userId: UUID) = dbQuery { ClubTable.selectAll().where { ClubTable.userId eq userId }.singleOrNull()?.toClub() }
    override suspend fun findById(id: UUID) = dbQuery { ClubTable.selectAll().where { ClubTable.id eq id }.singleOrNull()?.toClub() }
    override suspend fun create(club: Club) = dbQuery { ClubTable.insert { it.fill(club); it[id] = club.id; it[userId] = club.userId; it[createdAt] = club.createdAt }; club }
    override suspend fun update(club: Club) = dbQuery { ClubTable.update({ ClubTable.id eq club.id }) { it.fill(club) }; club }

    private fun UpdateBuilder<*>.fill(c: Club) {
        this[ClubTable.name] = c.name; this[ClubTable.primaryColor] = c.primaryColor; this[ClubTable.secondaryColor] = c.secondaryColor
        this[ClubTable.emblemJson] = c.emblemJson; this[ClubTable.level] = c.level; this[ClubTable.reputation] = c.reputation
        this[ClubTable.coins] = c.coins; this[ClubTable.gold] = c.gold; this[ClubTable.timezone] = c.timezone
        this[ClubTable.academyNextAt] = c.academyNextAt; this[ClubTable.medicalNextAt] = c.medicalNextAt; this[ClubTable.updatedAt] = c.updatedAt
    }

    private fun ResultRow.toClub() = Club(
        this[ClubTable.id], this[ClubTable.userId], this[ClubTable.name], this[ClubTable.primaryColor], this[ClubTable.secondaryColor], this[ClubTable.emblemJson],
        this[ClubTable.level], this[ClubTable.reputation], this[ClubTable.coins], this[ClubTable.gold], this[ClubTable.timezone],
        this[ClubTable.academyNextAt], this[ClubTable.medicalNextAt], this[ClubTable.createdAt], this[ClubTable.updatedAt],
    )
}

class BuildingRepositoryImpl : BuildingRepository {
    override suspend fun listByClub(clubId: UUID) = dbQuery { BuildingTable.selectAll().where { BuildingTable.clubId eq clubId }.orderBy(BuildingTable.createdAt).map { it.toBuilding() } }
    override suspend fun findById(id: UUID) = dbQuery { BuildingTable.selectAll().where { BuildingTable.id eq id }.singleOrNull()?.toBuilding() }
    override suspend fun create(building: Building) = dbQuery {
        BuildingTable.insert { it.fill(building); it[id] = building.id; it[clubId] = building.clubId; it[type] = building.type.name; it[gridX] = building.gridX; it[gridY] = building.gridY; it[rotation] = building.rotation; it[createdAt] = building.createdAt }
        building
    }
    override suspend fun update(building: Building) = dbQuery { BuildingTable.update({ BuildingTable.id eq building.id }) { it.fill(building) }; building }
    override suspend fun delete(id: UUID) { dbQuery { BuildingTable.deleteWhere { BuildingTable.id eq id } } }

    private fun UpdateBuilder<*>.fill(b: Building) {
        this[BuildingTable.level] = b.level; this[BuildingTable.state] = b.state.name; this[BuildingTable.upgradeEndsAt] = b.upgradeEndsAt
        this[BuildingTable.lastCollectedAt] = b.lastCollectedAt; this[BuildingTable.updatedAt] = b.updatedAt
    }

    private fun ResultRow.toBuilding() = Building(
        this[BuildingTable.id], this[BuildingTable.clubId], BuildingType.valueOf(this[BuildingTable.type]), this[BuildingTable.level],
        this[BuildingTable.gridX], this[BuildingTable.gridY], this[BuildingTable.rotation], BuildingState.valueOf(this[BuildingTable.state]),
        this[BuildingTable.upgradeEndsAt], this[BuildingTable.lastCollectedAt], this[BuildingTable.createdAt], this[BuildingTable.updatedAt],
    )
}
