package com.matchdayclub.domain.model

import java.time.Instant
import java.util.UUID

enum class AuthProvider { GUEST, GOOGLE, APPLE }

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
)
