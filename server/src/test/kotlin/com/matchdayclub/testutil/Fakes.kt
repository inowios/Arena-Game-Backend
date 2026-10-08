package com.matchdayclub.testutil

import com.matchdayclub.common.time.Clock
import com.matchdayclub.core.auth.IdTokenVerifier
import com.matchdayclub.core.auth.JwtUtil
import com.matchdayclub.core.auth.VerifiedIdentity
import com.matchdayclub.domain.exception.AuthenticationException
import com.matchdayclub.domain.model.AuthProvider
import com.matchdayclub.domain.model.RefreshToken
import com.matchdayclub.domain.model.User
import com.matchdayclub.domain.repository.RefreshTokenRepository
import com.matchdayclub.domain.repository.UserRepository
import java.time.Instant
import java.util.UUID

class FixedClock(var instant: Instant = Instant.parse("2026-10-09T12:00:00Z")) : Clock {
    override fun now(): Instant = instant
    fun advanceSeconds(s: Long) { instant = instant.plusSeconds(s) }
}

val testJwt = JwtUtil("test-secret", "test-issuer", "test-aud", 3_600_000, 30L * 24 * 3_600_000)

class FakeUserRepository : UserRepository {
    val rows = mutableMapOf<UUID, User>()
    override suspend fun findById(id: UUID) = rows[id]
    override suspend fun findByProvider(provider: AuthProvider, providerId: String) = rows.values.firstOrNull { it.authProvider == provider && it.providerId == providerId }
    override suspend fun findByEmail(email: String) = rows.values.firstOrNull { it.email == email }
    override suspend fun create(user: User): User { rows[user.id] = user; return user }
    override suspend fun update(user: User): User { rows[user.id] = user; return user }
}

class FakeRefreshTokenRepository : RefreshTokenRepository {
    val rows = mutableMapOf<UUID, RefreshToken>()
    override suspend fun create(token: RefreshToken): RefreshToken { rows[token.id] = token; return token }
    override suspend fun findByHash(hash: String) = rows.values.firstOrNull { it.tokenHash == hash }
    override suspend fun rotate(oldId: UUID, replacement: RefreshToken): Boolean {
        val old = rows[oldId] ?: return false
        if (old.revokedAt != null) return false
        rows[oldId] = old.copy(revokedAt = Instant.now()); rows[replacement.id] = replacement; return true
    }
    override suspend fun revokeByHash(hash: String) { rows.values.filter { it.tokenHash == hash }.forEach { rows[it.id] = it.copy(revokedAt = Instant.now()) } }
    override suspend fun revokeAllForUser(userId: UUID) { rows.values.filter { it.userId == userId }.forEach { rows[it.id] = it.copy(revokedAt = Instant.now()) } }
}

class FakeIdTokenVerifier(private val identities: Map<String, VerifiedIdentity>) : IdTokenVerifier {
    override suspend fun verify(idToken: String) = identities[idToken] ?: throw AuthenticationException("bad token")
}
