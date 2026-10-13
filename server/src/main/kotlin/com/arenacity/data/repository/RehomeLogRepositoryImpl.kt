package com.arenacity.data.repository

import com.arenacity.common.extension.dbQuery
import com.arenacity.data.database.RehomeLogTable
import com.arenacity.domain.repository.RehomeLogRepository
import com.arenacity.domain.repository.RehomeMove
import org.jetbrains.exposed.sql.batchInsert

class RehomeLogRepositoryImpl : RehomeLogRepository {
    override suspend fun record(moves: List<RehomeMove>) {
        if (moves.isEmpty()) return
        dbQuery {
            RehomeLogTable.batchInsert(moves, shouldReturnGeneratedValues = false) { m ->
                this[RehomeLogTable.clubId] = m.clubId; this[RehomeLogTable.itemId] = m.itemId; this[RehomeLogTable.kind] = m.kind; this[RehomeLogTable.type] = m.type
                this[RehomeLogTable.oldX] = m.oldX; this[RehomeLogTable.oldY] = m.oldY; this[RehomeLogTable.oldRotation] = m.oldRotation
                this[RehomeLogTable.newX] = m.newX; this[RehomeLogTable.newY] = m.newY; this[RehomeLogTable.newRotation] = m.newRotation
                this[RehomeLogTable.gold] = m.gold; this[RehomeLogTable.maskId] = m.maskId; this[RehomeLogTable.at] = m.at
                this[RehomeLogTable.level] = m.level; this[RehomeLogTable.coins] = m.coins
            }
        }
    }
}
