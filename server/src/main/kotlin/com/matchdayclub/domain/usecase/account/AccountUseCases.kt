package com.matchdayclub.domain.usecase.account

import com.matchdayclub.common.time.Clock
import com.matchdayclub.common.tx.NoTx
import com.matchdayclub.common.tx.Tx
import com.matchdayclub.domain.exception.AuthorizationException
import com.matchdayclub.domain.exception.ConflictException
import com.matchdayclub.domain.exception.NotFoundException
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.repository.*
import com.matchdayclub.domain.usecase.auth.AuthResult
import com.matchdayclub.domain.usecase.auth.TokenIssuer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.UUID

/** A club whose player left keeps playing as a bot, so league tables and fixtures stay complete. */
class ClubRetirement(private val clubs: ClubRepository, private val leagues: LeagueRepository, private val accounts: AccountRepository, private val clock: Clock) {
    private val names = listOf("Nordwind", "Riverside", "Ironbridge", "Harbor City", "Granite Rovers", "Oakfield", "Steel Valley", "Red Canyon", "Silver Lake", "Westgate", "Falcon Park", "Copper Town")

    suspend fun retire(club: Club) {
        val now = clock.now()
        for (l in leagues.listForClub(club.id).filter { it.kind == LeagueKind.PRIVATE }) {
            leagues.removeMember(l.id, club.id)
            if (l.createdBy == club.id) leagues.update(l.copy(createdBy = leagues.members(l.id).firstOrNull(), updatedAt = now))
        }
        accounts.hideClubMessages(club.id)
        clubs.update(club.copy(isBot = true, name = "FC " + names[Math.floorMod(club.id.hashCode(), names.size)], emblemJson = null, kit = null, emblemFrame = null, celebration = null, updatedAt = now))
    }
}

/**
 * Moving an account to another device without a store login: the old device shows a one-time code (24 h),
 * the new device redeems it. A guest account is re-bound to the new device id (a fresh guest created there is detached).
 */
class TransferUseCases(
    private val users: UserRepository,
    private val clubs: ClubRepository,
    private val accounts: AccountRepository,
    private val issuer: TokenIssuer,
    private val retirement: ClubRetirement,
    private val clock: Clock,
    private val tx: Tx = NoTx,
) {
    companion object {
        const val TTL_SECONDS = 24 * 3600L
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        fun normalize(code: String) = code.uppercase().filter { it.isLetterOrDigit() }
        fun display(code: String) = code.chunked(4).joinToString("-")
    }
    private val random = SecureRandom()

    suspend fun createCode(userId: UUID): TransferCode {
        val user = users.findById(userId) ?: throw NotFoundException("User not found")
        if (!user.isActive) throw AuthorizationException("Account deactivated")
        val now = clock.now()
        val code = (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        return TransferCode(code, user.id, now.plusSeconds(TTL_SECONDS), null, now).also { accounts.createTransferCode(it) }
    }

    suspend fun redeem(rawCode: String, deviceId: String): AuthResult = tx {
        if (deviceId.length !in 8..128) throw ValidationException("deviceId must be 8..128 chars")
        val code = normalize(rawCode)
        val now = clock.now()
        val tc = accounts.findTransferCode(code) ?: throw NotFoundException("Transfer code not found")
        if (tc.usedAt != null || now.isAfter(tc.expiresAt)) throw ConflictException("This transfer code has expired")
        if (!accounts.useTransferCode(code, now)) throw ConflictException("This transfer code has expired")
        var user = users.findById(tc.userId) ?: throw NotFoundException("User not found")
        if (!user.isActive) throw AuthorizationException("Account deactivated")
        if (user.authProvider == AuthProvider.GUEST && user.providerId != deviceId) {
            users.findByProvider(AuthProvider.GUEST, deviceId)?.takeIf { it.id != user.id }?.let { fresh ->
                clubs.findByUserId(fresh.id)?.let { retirement.retire(it) }
                users.update(fresh.copy(providerId = "detached:${fresh.id}", isActive = false, updatedAt = now))
            }
            user = users.update(user.copy(providerId = deviceId, updatedAt = now))
        }
        val t = issuer.issue(user)
        AuthResult(user, t.accessToken, t.refreshToken, false)
    }
}

/** In-app account deletion (store policies): sign-in data is erased, the club is retired into a bot. */
class DeleteAccountUseCase(
    private val users: UserRepository,
    private val clubs: ClubRepository,
    private val accounts: AccountRepository,
    private val refreshTokens: RefreshTokenRepository,
    private val retirement: ClubRetirement,
    private val clock: Clock,
    private val tx: Tx = NoTx,
) {
    suspend operator fun invoke(userId: UUID) = tx {
        val user = users.findById(userId) ?: throw NotFoundException("User not found")
        clubs.lockByUserId(userId)?.let { retirement.retire(it) }
        accounts.deletePushTokens(userId)
        refreshTokens.revokeAllForUser(userId)
        users.update(user.copy(isActive = false, providerId = "deleted:${user.id}", email = null, displayName = null, avatarUrl = null, locale = null, updatedAt = clock.now()))
        Unit
    }
}

/** Keeps what pushes announced, for the in-app inbox. Never throws into the caller. */
class InboxService(private val repo: InboxRepository, private val clock: Clock) {
    suspend fun add(clubId: UUID, kind: String, data: Map<String, String>) {
        runCatching { repo.add(InboxItem(UUID.randomUUID(), clubId, kind, Json.encodeToString(data), clock.now(), null)) }
            .onFailure { org.slf4j.LoggerFactory.getLogger("Inbox").warn("inbox add failed", it) }
    }
    suspend fun list(clubId: UUID, limit: Int = 50) = repo.list(clubId, limit.coerceIn(1, 100))
    suspend fun unread(clubId: UUID) = repo.unread(clubId)
    suspend fun markAllRead(clubId: UUID) = repo.markAllRead(clubId, clock.now())
}
