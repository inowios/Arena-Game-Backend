package com.arenacity.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object RefreshTokenTable : Table("refresh_tokens") {
    val id = uuid("id")
    val userId = uuid("user_id").references(UserTable.id)
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val expiresAt = timestamp("expires_at")
    val revokedAt = timestamp("revoked_at").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
