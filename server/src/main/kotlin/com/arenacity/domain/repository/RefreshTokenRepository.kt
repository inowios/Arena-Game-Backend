package com.arenacity.domain.repository

import com.arenacity.domain.model.RefreshToken
import java.util.UUID

interface RefreshTokenRepository {
    suspend fun create(token: RefreshToken): RefreshToken
    suspend fun findByHash(hash: String): RefreshToken?
    /** Atomically revokes [oldId] (only if not yet revoked) and stores [replacement]. Returns false if a concurrent refresh already won. */
    suspend fun rotate(oldId: UUID, replacement: RefreshToken): Boolean
    suspend fun revokeByHash(hash: String)
    suspend fun revokeAllForUser(userId: UUID)
}
