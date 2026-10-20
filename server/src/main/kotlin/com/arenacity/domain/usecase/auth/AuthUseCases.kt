package com.arenacity.domain.usecase.auth

import com.arenacity.common.time.Clock
import com.arenacity.core.auth.JwtUtil
import com.arenacity.domain.exception.AuthenticationException
import com.arenacity.domain.exception.AuthorizationException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.AuthProvider
import com.arenacity.domain.model.RefreshToken
import com.arenacity.domain.model.User
import com.arenacity.domain.repository.RefreshTokenRepository
import com.arenacity.domain.repository.UserRepository
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
