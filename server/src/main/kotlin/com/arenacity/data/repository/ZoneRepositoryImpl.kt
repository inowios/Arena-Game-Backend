package com.arenacity.data.repository

import com.arenacity.common.extension.dbQuery
import com.arenacity.data.database.ClubZoneTable
import com.arenacity.domain.model.ClubZone
import com.arenacity.domain.model.ZoneSport
import com.arenacity.domain.model.ZoneState
import com.arenacity.domain.repository.ZoneRepository
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert
import java.util.UUID

class ZoneRepositoryImpl : ZoneRepository {
    override suspend fun listByClub(clubId: UUID) = dbQuery {
        ClubZoneTable.selectAll().where { ClubZoneTable.clubId eq clubId }.map { it.toZone() }
    }
    override suspend fun upsert(zone: ClubZone) = dbQuery {
        ClubZoneTable.upsert {
            it[clubId] = zone.clubId; it[sport] = zone.sport.name; it[state] = zone.state.name; it[boughtAt] = zone.boughtAt; it[restoreEndsAt] = zone.restoreEndsAt
        }
        zone
    }
    private fun ResultRow.toZone() = ClubZone(this[ClubZoneTable.clubId], ZoneSport.valueOf(this[ClubZoneTable.sport]), ZoneState.valueOf(this[ClubZoneTable.state]),
        this[ClubZoneTable.boughtAt], this[ClubZoneTable.restoreEndsAt])
}
