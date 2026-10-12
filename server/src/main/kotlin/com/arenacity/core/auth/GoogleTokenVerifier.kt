package com.arenacity.core.auth

import com.arenacity.domain.exception.AuthenticationException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable
private data class TokenInfo(val sub: String? = null, val aud: String? = null, val email: String? = null, val name: String? = null, val exp: String? = null, val email_verified: String? = null)

/** Validates a Google ID token through the tokeninfo endpoint and checks the audience against our client ids. */
class GoogleTokenVerifier(private val client: HttpClient, private val allowedClientIds: Set<String>) : IdTokenVerifier {
    override suspend fun verify(idToken: String): VerifiedIdentity {
        val resp = client.get("https://oauth2.googleapis.com/tokeninfo") { parameter("id_token", idToken) }
        if (resp.status != HttpStatusCode.OK) throw AuthenticationException("Invalid Google token")
        val info: TokenInfo = resp.body()
        val sub = info.sub ?: throw AuthenticationException("Invalid Google token")
        if (allowedClientIds.isNotEmpty() && info.aud !in allowedClientIds) throw AuthenticationException("Google token audience mismatch")
        val exp = info.exp?.toLongOrNull() ?: 0L
        if (exp * 1000 < System.currentTimeMillis()) throw AuthenticationException("Google token expired")
        return VerifiedIdentity(sub, info.email, info.name)
    }
}
