package com.matchdayclub.domain.usecase.auth

import com.matchdayclub.common.time.Clock
import com.matchdayclub.core.auth.IdTokenVerifier
import com.matchdayclub.core.auth.JwtUtil
import com.matchdayclub.domain.exception.AuthenticationException
import com.matchdayclub.domain.exception.AuthorizationException
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.AuthProvider
import com.matchdayclub.domain.model.RefreshToken
import com.matchdayclub.domain.model.User
import com.matchdayclub.domain.repository.RefreshTokenRepository
import com.matchdayclub.domain.repository.UserRepository
import java.util.UUID

data class AuthResult(val user: User, val accessToken: String, val refreshToken: String, val isNewUser: Boolean)
data class TokenPair(val accessToken: String, val refreshToken: String)

/** Shared token issuing for every login path. */
class TokenIssuer(private val refreshTokens: RefreshTokenRepository, private val jwt: JwtUtil, private val clock: Clock) {
    suspend fun issue(user: User): TokenPair {
        val raw = jwt.newRefreshToken()
        val now = clock.now()
        refreshTokens.create(RefreshToken(UUID.randomUUID(), user.id, JwtUtil.hash(raw), now.plusMillis(jwt.refreshTokenExpiryMs), null, now))
        return TokenPair(jwt.generateAccessToken(user.id.toString(), mapOf("provider" to user.authProvider.name)), raw)
    }
}

class GuestLoginUseCase(private val users: UserRepository, private val issuer: TokenIssuer, private val clock: Clock) {
    suspend operator fun invoke(deviceId: String): AuthResult {
        if (deviceId.length !in 8..128) throw ValidationException("deviceId must be 8..128 chars")
        val existing = users.findByProvider(AuthProvider.GUEST, deviceId)
        val user = existing ?: users.create(User(UUID.randomUUID(), AuthProvider.GUEST, deviceId, null, null, null, true, clock.now(), clock.now()))
        if (!user.isActive) throw AuthorizationException("Account deactivated")
        val tokens = issuer.issue(user)
        return AuthResult(user, tokens.accessToken, tokens.refreshToken, existing == null)
    }
}

class OAuthLoginUseCase(
    private val users: UserRepository,
    private val issuer: TokenIssuer,
    private val clock: Clock,
    private val verifiers: Map<AuthProvider, IdTokenVerifier>,
) {
    /**
     * @param currentUserId a logged-in GUEST user to upgrade in place (keeps their club), or null.
     */
    suspend operator fun invoke(provider: AuthProvider, idToken: String, currentUserId: UUID?): AuthResult {
        val verifier = verifiers[provider] ?: throw ValidationException("Unsupported provider $provider")
        val identity = verifier.verify(idToken)
        val now = clock.now()
        users.findByProvider(provider, identity.providerId)?.let { linked ->
            if (!linked.isActive) throw AuthorizationException("Account deactivated")
            val t = issuer.issue(linked)
            return AuthResult(linked, t.accessToken, t.refreshToken, false)
        }
        val guest = currentUserId?.let { users.findById(it) }?.takeIf { it.authProvider == AuthProvider.GUEST }
        val byEmail = identity.email?.let { users.findByEmail(it) }
        val user = when {
            guest != null -> users.update(guest.copy(authProvider = provider, providerId = identity.providerId, email = identity.email ?: guest.email, displayName = guest.displayName ?: identity.name, updatedAt = now))
            byEmail != null -> users.update(byEmail.copy(authProvider = provider, providerId = identity.providerId, updatedAt = now))
            else -> users.create(User(UUID.randomUUID(), provider, identity.providerId, identity.email, identity.name, null, true, now, now))
        }
        if (!user.isActive) throw AuthorizationException("Account deactivated")
        val t = issuer.issue(user)
        return AuthResult(user, t.accessToken, t.refreshToken, guest == null && byEmail == null)
    }
}

class RefreshTokenUseCase(private val refreshTokens: RefreshTokenRepository, private val users: UserRepository, private val jwt: JwtUtil, private val clock: Clock) {
    suspend operator fun invoke(rawToken: String): TokenPair {
        val now = clock.now()
        val stored = refreshTokens.findByHash(JwtUtil.hash(rawToken)) ?: throw AuthenticationException("Invalid refresh token")
        if (!stored.isValid(now)) throw AuthenticationException("Refresh token expired or revoked")
        val user = users.findById(stored.userId) ?: throw AuthenticationException("User not found")
        val raw = jwt.newRefreshToken()
        val replacement = RefreshToken(UUID.randomUUID(), user.id, JwtUtil.hash(raw), now.plusMillis(jwt.refreshTokenExpiryMs), null, now)
        if (!refreshTokens.rotate(stored.id, replacement)) throw AuthenticationException("Refresh token already used")
        return TokenPair(jwt.generateAccessToken(user.id.toString(), mapOf("provider" to user.authProvider.name)), raw)
    }
}

class LogoutUseCase(private val refreshTokens: RefreshTokenRepository) {
    suspend fun logout(rawToken: String) = refreshTokens.revokeByHash(JwtUtil.hash(rawToken))
    suspend fun logoutAll(userId: UUID) = refreshTokens.revokeAllForUser(userId)
}
