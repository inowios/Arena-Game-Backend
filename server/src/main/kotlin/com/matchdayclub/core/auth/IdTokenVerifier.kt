package com.matchdayclub.core.auth

data class VerifiedIdentity(val providerId: String, val email: String?, val name: String?)

/** Verifies an OAuth ID token server-side and extracts the stable subject. Must throw AuthenticationException on failure. */
fun interface IdTokenVerifier {
    suspend fun verify(idToken: String): VerifiedIdentity
}
