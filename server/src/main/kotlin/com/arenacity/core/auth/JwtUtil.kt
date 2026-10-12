package com.arenacity.core.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.MessageDigest
import java.util.Date
import java.util.UUID

class JwtUtil(
    private val secret: String,
    private val issuer: String,
    private val audience: String,
    val accessTokenExpiryMs: Long,
    val refreshTokenExpiryMs: Long,
) {
    fun generateAccessToken(userId: String, claims: Map<String, String> = emptyMap()): String =
        JWT.create().withAudience(audience).withIssuer(issuer).withSubject(userId)
            .apply { claims.forEach { (k, v) -> withClaim(k, v) } }
            .withExpiresAt(Date(System.currentTimeMillis() + accessTokenExpiryMs))
            .sign(Algorithm.HMAC256(secret))

    fun newRefreshToken(): String = UUID.randomUUID().toString() + UUID.randomUUID().toString().replace("-", "")

    companion object {
        fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
