package com.arenacity.domain.usecase

import com.arenacity.domain.exception.AuthenticationException
import com.arenacity.domain.usecase.auth.*
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class AuthUseCasesTest {
    private val clock = FixedClock()
    private val users = FakeUserRepository()
    private val tokens = FakeRefreshTokenRepository()
    private val issuer = TokenIssuer(tokens, testJwt, clock)
    private val guest = GuestLoginUseCase(users, issuer, clock)
    private val refresh = RefreshTokenUseCase(tokens, users, testJwt, clock)

    @Test
    fun `guest login creates once and reuses`() = runBlocking<Unit> {
        val a = guest("device-12345678")
        val b = guest("device-12345678")
        assertTrue(a.isNewUser); assertFalse(b.isNewUser); assertEquals(a.user.id, b.user.id)
        assertEquals(1, users.rows.size); assertEquals(2, tokens.rows.size)
    }

    @Test
    fun `refresh rotates and rejects reuse`() = runBlocking<Unit> {
        val a = guest("device-12345678")
        val pair = refresh(a.refreshToken)
        assertNotEquals(a.refreshToken, pair.refreshToken)
        assertFailsWith<AuthenticationException> { refresh(a.refreshToken) }
        clock.advanceSeconds(31L * 24 * 3600)
        assertFailsWith<AuthenticationException> { refresh(pair.refreshToken) }
        Unit
    }
}
