package com.matchdayclub.domain.usecase

import com.matchdayclub.core.auth.VerifiedIdentity
import com.matchdayclub.domain.exception.AuthenticationException
import com.matchdayclub.domain.model.AuthProvider
import com.matchdayclub.domain.usecase.auth.*
import com.matchdayclub.testutil.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AuthUseCasesTest {
    private val clock = FixedClock()
    private val users = FakeUserRepository()
    private val tokens = FakeRefreshTokenRepository()
    private val issuer = TokenIssuer(tokens, testJwt, clock)
    private val guest = GuestLoginUseCase(users, issuer, clock)
    private val verifier = FakeIdTokenVerifier(mapOf("g-ok" to VerifiedIdentity("google-sub-1", "a@b.com", "Ann"), "ap-ok" to VerifiedIdentity("apple-sub-1", "c@d.com", null)))
    private val oauth = OAuthLoginUseCase(users, issuer, clock, mapOf(AuthProvider.GOOGLE to verifier, AuthProvider.APPLE to verifier))
    private val refresh = RefreshTokenUseCase(tokens, users, testJwt, clock)

    @Test
    fun `guest login creates once and reuses`() = runBlocking {
        val a = guest("device-12345678")
        val b = guest("device-12345678")
        assertTrue(a.isNewUser); assertFalse(b.isNewUser); assertEquals(a.user.id, b.user.id)
        assertEquals(1, users.rows.size); assertEquals(2, tokens.rows.size)
    }

    @Test
    fun `guest upgrades to google in place keeping id`() = runBlocking {
        val g = guest("device-12345678")
        val up = oauth(AuthProvider.GOOGLE, "g-ok", g.user.id)
        assertEquals(g.user.id, up.user.id); assertEquals(AuthProvider.GOOGLE, up.user.authProvider); assertEquals("a@b.com", up.user.email); assertFalse(up.isNewUser)
        val again = oauth(AuthProvider.GOOGLE, "g-ok", null)
        assertEquals(g.user.id, again.user.id)
    }

    @Test
    fun `oauth with bad token fails`() = runBlocking {
        assertFailsWith<AuthenticationException> { oauth(AuthProvider.APPLE, "nope", null) }
        Unit
    }

    @Test
    fun `refresh rotates and rejects reuse`() = runBlocking {
        val a = guest("device-12345678")
        val pair = refresh(a.refreshToken)
        assertNotEquals(a.refreshToken, pair.refreshToken)
        assertFailsWith<AuthenticationException> { refresh(a.refreshToken) }
        clock.advanceSeconds(31L * 24 * 3600)
        assertFailsWith<AuthenticationException> { refresh(pair.refreshToken) }
        Unit
    }
}
