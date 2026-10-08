package com.matchdayclub.core.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.RSAKeyProvider
import com.matchdayclub.domain.exception.AuthenticationException
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

@Serializable
private data class Jwk(val kid: String, val n: String, val e: String)

@Serializable
private data class Jwks(val keys: List<Jwk>)

/** Verifies Sign in with Apple identity tokens with Apple's JWKS (cached for an hour). */
class AppleTokenVerifier(private val client: HttpClient, private val bundleId: String) : IdTokenVerifier {
    @Volatile private var cache: Pair<Long, Map<String, RSAPublicKey>>? = null

    private suspend fun keys(): Map<String, RSAPublicKey> {
        val c = cache
        if (c != null && System.currentTimeMillis() - c.first < 3_600_000) return c.second
        val jwks: Jwks = client.get("https://appleid.apple.com/auth/keys").body()
        val kf = KeyFactory.getInstance("RSA")
        val map = jwks.keys.associate { k ->
            val n = BigInteger(1, Base64.getUrlDecoder().decode(k.n))
            val e = BigInteger(1, Base64.getUrlDecoder().decode(k.e))
            k.kid to kf.generatePublic(RSAPublicKeySpec(n, e)) as RSAPublicKey
        }
        cache = System.currentTimeMillis() to map
        return map
    }

    override suspend fun verify(idToken: String): VerifiedIdentity {
        val keys = keys()
        val provider = object : RSAKeyProvider {
            override fun getPublicKeyById(keyId: String?): RSAPublicKey = keys[keyId] ?: throw AuthenticationException("Unknown Apple key")
            override fun getPrivateKey(): RSAPrivateKey? = null
            override fun getPrivateKeyId(): String? = null
        }
        val decoded = try {
            JWT.require(Algorithm.RSA256(provider)).withIssuer("https://appleid.apple.com").withAudience(bundleId).build().verify(idToken)
        } catch (e: Exception) {
            throw AuthenticationException("Invalid Apple token")
        }
        return VerifiedIdentity(decoded.subject, decoded.getClaim("email").asString(), null)
    }
}
