package com.arenacity.data.repository

import com.arenacity.common.extension.dbQuery
import com.arenacity.data.database.LandParcelTable
import com.arenacity.domain.repository.LandRepository
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import java.time.Instant
import java.util.UUID

class LandRepositoryImpl : LandRepository {
    override suspend fun listByClub(clubId: UUID) = dbQuery {
        LandParcelTable.selectAll().where { LandParcelTable.clubId eq clubId }.map { it[LandParcelTable.col] to it[LandParcelTable.row] }
    }
    override suspend fun add(clubId: UUID, col: Int, row: Int, at: Instant) {
        dbQuery { LandParcelTable.insertIgnore { it[LandParcelTable.clubId] = clubId; it[LandParcelTable.col] = col; it[LandParcelTable.row] = row; it[boughtAt] = at } }
    }
}
