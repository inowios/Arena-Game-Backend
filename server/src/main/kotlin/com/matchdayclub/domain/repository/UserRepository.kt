package com.matchdayclub.domain.repository

import com.matchdayclub.domain.model.AuthProvider
import com.matchdayclub.domain.model.User
import java.util.UUID

interface UserRepository {
    suspend fun findById(id: UUID): User?
    suspend fun findByProvider(provider: AuthProvider, providerId: String): User?
    suspend fun findByEmail(email: String): User?
    suspend fun create(user: User): User
    suspend fun update(user: User): User
    /** Stored language per user (null = unknown). */
    suspend fun locales(ids: Collection<UUID>): Map<UUID, String?> = ids.associateWith { findById(it)?.locale }
}
