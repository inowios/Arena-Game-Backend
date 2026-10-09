package com.matchdayclub.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object InboxTable : Table("inbox") {
    val id = uuid("id"); val clubId = uuid("club_id"); val kind = varchar("kind", 24); val dataJson = text("data_json")
    val createdAt = timestamp("created_at"); val readAt = timestamp("read_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object TransferCodeTable : Table("transfer_codes") {
    val code = varchar("code", 12); val userId = uuid("user_id"); val expiresAt = timestamp("expires_at"); val usedAt = timestamp("used_at").nullable(); val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(code)
}
