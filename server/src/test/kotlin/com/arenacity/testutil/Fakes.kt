package com.arenacity.testutil

import com.arenacity.common.time.Clock
import com.arenacity.core.auth.IdTokenVerifier
import com.arenacity.core.auth.JwtUtil
import com.arenacity.core.auth.VerifiedIdentity
import com.arenacity.domain.exception.AuthenticationException
import com.arenacity.domain.model.AuthProvider
import com.arenacity.domain.model.RefreshToken
import com.arenacity.domain.model.User
import com.arenacity.domain.repository.RefreshTokenRepository
import com.arenacity.domain.repository.UserRepository
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

class FakeAccountRepository : com.arenacity.domain.repository.AccountRepository {
    val codes = mutableMapOf<String, com.arenacity.domain.model.TransferCode>()
    val deletedPushFor = mutableSetOf<UUID>(); val hiddenFor = mutableSetOf<UUID>()
    override suspend fun createTransferCode(code: com.arenacity.domain.model.TransferCode) { codes.values.removeIf { it.userId == code.userId }; codes[code.code] = code }
    override suspend fun findTransferCode(code: String) = codes[code]
    override suspend fun useTransferCode(code: String, at: Instant): Boolean { val c = codes[code] ?: return false; if (c.usedAt != null) return false; codes[code] = c.copy(usedAt = at); return true }
    override suspend fun deletePushTokens(userId: UUID) { deletedPushFor += userId }
    override suspend fun hideClubMessages(clubId: UUID) { hiddenFor += clubId }
}

class FakeInboxRepository : com.arenacity.domain.repository.InboxRepository {
    val rows = mutableListOf<com.arenacity.domain.model.InboxItem>()
    override suspend fun add(item: com.arenacity.domain.model.InboxItem) { rows += item }
    override suspend fun list(clubId: UUID, limit: Int) = rows.filter { it.clubId == clubId }.sortedByDescending { it.createdAt }.take(limit)
    override suspend fun unread(clubId: UUID) = rows.count { it.clubId == clubId && it.readAt == null }
    override suspend fun markAllRead(clubId: UUID, at: Instant) { rows.replaceAll { if (it.clubId == clubId && it.readAt == null) it.copy(readAt = at) else it } }
}
