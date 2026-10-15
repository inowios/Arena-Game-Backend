package com.arenacity.data.repository

import com.arenacity.common.extension.dbQuery
import com.arenacity.data.database.ClubZoneTable
import com.arenacity.domain.model.ClubZone
import com.arenacity.domain.model.People
import com.arenacity.domain.model.ZoneState
import com.arenacity.domain.repository.ZoneRepository
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert
import java.util.UUID

class ZoneRepositoryImpl : ZoneRepository {
    override suspend fun upsert(zone: ClubZone) = dbQuery {
        ClubZoneTable.upsert {
            it[clubId] = zone.clubId; it[sport] = zone.people.name; it[state] = zone.state.name; it[boughtAt] = zone.boughtAt; it[restoreEndsAt] = zone.restoreEndsAt
        }
        zone
    }
    override suspend fun listByClub(clubId: UUID) = dbQuery {
        ClubZoneTable.selectAll().where { ClubZoneTable.clubId eq clubId }.mapNotNull { it.toZone() }
    }
    /** The column keeps its old name (`sport`); it holds the people (V44 renamed the rows; [People.parse] still reads an old one). */
    private fun ResultRow.toZone() = People.parse(this[ClubZoneTable.sport])?.let { p ->
        ClubZone(this[ClubZoneTable.clubId], p, ZoneState.valueOf(this[ClubZoneTable.state]), this[ClubZoneTable.boughtAt], this[ClubZoneTable.restoreEndsAt])
    }
}
