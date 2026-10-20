package com.arenacity.presentation.dto

import com.arenacity.domain.model.User
import kotlinx.serialization.Serializable

@Serializable data class GuestLoginRequest(val deviceId: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class UserDto(val id: String, val provider: String, val email: String?, val displayName: String?, val avatarUrl: String?, val locale: String? = null)
@Serializable data class UpdateMeRequest(val locale: String? = null)
@Serializable data class AuthResponse(val accessToken: String, val refreshToken: String, val user: UserDto, val isNewUser: Boolean)
@Serializable data class TokenResponse(val accessToken: String, val refreshToken: String)
@Serializable data class MeResponse(val user: UserDto, val hasClub: Boolean)

fun User.toDto() = UserDto(id.toString(), authProvider.name, email, displayName, avatarUrl, locale)

@Serializable data class TransferCodeResponse(val code: String, val expiresAt: String)
@Serializable data class TransferRequest(val code: String, val deviceId: String)
@Serializable data class InboxItemDto(val id: String, val kind: String, val data: Map<String, String>, val createdAt: String, val read: Boolean)
@Serializable data class InboxResponse(val items: List<InboxItemDto>, val unread: Int)

fun com.arenacity.domain.model.InboxItem.toDto() = InboxItemDto(id.toString(), kind,
    runCatching { kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(dataJson) }.getOrDefault(emptyMap()), createdAt.toString(), readAt != null)
