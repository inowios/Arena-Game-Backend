package com.matchdayclub.data.repository

import com.matchdayclub.common.extension.dbQuery
import com.matchdayclub.data.database.RefreshTokenTable
import com.matchdayclub.domain.model.RefreshToken
import com.matchdayclub.domain.repository.RefreshTokenRepository
import org.jetbrains.exposed.sql.*
import java.time.Instant
import java.util.UUID

class RefreshTokenRepositoryImpl : RefreshTokenRepository {
    override suspend fun create(token: RefreshToken): RefreshToken = dbQuery { insertRow(token); token }

    override suspend fun findByHash(hash: String): RefreshToken? = dbQuery {
        RefreshTokenTable.selectAll().where { RefreshTokenTable.tokenHash eq hash }.singleOrNull()?.toToken()
    }

    override suspend fun rotate(oldId: UUID, replacement: RefreshToken): Boolean = dbQuery {
        val revoked = RefreshTokenTable.update({ (RefreshTokenTable.id eq oldId) and RefreshTokenTable.revokedAt.isNull() }) { it[revokedAt] = Instant.now() }
        if (revoked == 1) { insertRow(replacement); true } else false
    }

    override suspend fun revokeByHash(hash: String) {
        dbQuery { RefreshTokenTable.update({ (RefreshTokenTable.tokenHash eq hash) and RefreshTokenTable.revokedAt.isNull() }) { it[revokedAt] = Instant.now() } }
    }

    override suspend fun revokeAllForUser(userId: UUID) {
        dbQuery { RefreshTokenTable.update({ (RefreshTokenTable.userId eq userId) and RefreshTokenTable.revokedAt.isNull() }) { it[revokedAt] = Instant.now() } }
    }

    private fun insertRow(t: RefreshToken) {
        RefreshTokenTable.insert { it[id] = t.id; it[userId] = t.userId; it[tokenHash] = t.tokenHash; it[expiresAt] = t.expiresAt; it[revokedAt] = t.revokedAt; it[createdAt] = t.createdAt }
    }

    private fun ResultRow.toToken() = RefreshToken(this[RefreshTokenTable.id], this[RefreshTokenTable.userId], this[RefreshTokenTable.tokenHash], this[RefreshTokenTable.expiresAt], this[RefreshTokenTable.revokedAt], this[RefreshTokenTable.createdAt])
}
