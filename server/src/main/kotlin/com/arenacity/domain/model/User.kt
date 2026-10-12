package com.arenacity.domain.model

import java.time.Instant
import java.util.UUID

enum class AuthProvider { GUEST, GOOGLE, APPLE, BOT }

data class User(
    val id: UUID,
    val authProvider: AuthProvider,
    val providerId: String,
    val email: String?,
    val displayName: String?,
    val avatarUrl: String?,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** en / es / pt / ru / uk; null until the app reported it. */
    val locale: String? = null,
)
