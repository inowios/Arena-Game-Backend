package com.matchdayclub.domain.model

import java.time.Instant
import java.util.UUID

data class RefreshToken(val id: UUID, val userId: UUID, val tokenHash: String, val expiresAt: Instant, val revokedAt: Instant?, val createdAt: Instant) {
    fun isValid(now: Instant) = revokedAt == null && expiresAt.isAfter(now)
}
