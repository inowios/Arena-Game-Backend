package com.matchdayclub.data.repository

import com.matchdayclub.common.extension.dbQuery
import com.matchdayclub.data.database.*
import com.matchdayclub.domain.model.InboxItem
import com.matchdayclub.domain.model.TransferCode
import com.matchdayclub.domain.repository.AccountRepository
import com.matchdayclub.domain.repository.InboxRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant
import java.util.UUID

class AccountRepositoryImpl : AccountRepository {
    override suspend fun createTransferCode(code: TransferCode) {
        dbQuery {
            TransferCodeTable.deleteWhere { TransferCodeTable.userId eq code.userId }
            TransferCodeTable.insert { it[this.code] = code.code; it[userId] = code.userId; it[expiresAt] = code.expiresAt; it[usedAt] = null; it[createdAt] = code.createdAt }
        }
    }
    override suspend fun findTransferCode(code: String) = dbQuery {
        TransferCodeTable.selectAll().where { TransferCodeTable.code eq code }.singleOrNull()?.let {
            TransferCode(it[TransferCodeTable.code], it[TransferCodeTable.userId], it[TransferCodeTable.expiresAt], it[TransferCodeTable.usedAt], it[TransferCodeTable.createdAt])
        }
    }
    override suspend fun useTransferCode(code: String, at: Instant): Boolean = dbQuery {
        TransferCodeTable.update({ (TransferCodeTable.code eq code) and TransferCodeTable.usedAt.isNull() }) { it[usedAt] = at } == 1
    }
    override suspend fun deletePushTokens(userId: UUID) { dbQuery { PushTokenTable.deleteWhere { PushTokenTable.userId eq userId } } }
    override suspend fun hideClubMessages(clubId: UUID) { dbQuery { LeagueMessageTable.update({ LeagueMessageTable.clubId eq clubId }) { it[deleted] = true } } }
}

class InboxRepositoryImpl : InboxRepository {
    override suspend fun add(item: InboxItem) {
        dbQuery { InboxTable.insert { it[id] = item.id; it[clubId] = item.clubId; it[kind] = item.kind; it[dataJson] = item.dataJson; it[createdAt] = item.createdAt; it[readAt] = item.readAt } }
    }
    override suspend fun list(clubId: UUID, limit: Int) = dbQuery {
        InboxTable.selectAll().where { InboxTable.clubId eq clubId }.orderBy(InboxTable.createdAt, SortOrder.DESC).limit(limit)
            .map { InboxItem(it[InboxTable.id], it[InboxTable.clubId], it[InboxTable.kind], it[InboxTable.dataJson], it[InboxTable.createdAt], it[InboxTable.readAt]) }
    }
    override suspend fun unread(clubId: UUID): Int = dbQuery { InboxTable.selectAll().where { (InboxTable.clubId eq clubId) and InboxTable.readAt.isNull() }.count().toInt() }
    override suspend fun markAllRead(clubId: UUID, at: Instant) { dbQuery { InboxTable.update({ (InboxTable.clubId eq clubId) and InboxTable.readAt.isNull() }) { it[readAt] = at } } }
}
