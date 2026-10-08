package com.matchdayclub.presentation.dto

import com.matchdayclub.domain.model.User
import kotlinx.serialization.Serializable

@Serializable data class GuestLoginRequest(val deviceId: String)
@Serializable data class OAuthLoginRequest(val idToken: String)
@Serializable data class RefreshRequest(val refreshToken: String)
@Serializable data class UserDto(val id: String, val provider: String, val email: String?, val displayName: String?, val avatarUrl: String?)
@Serializable data class AuthResponse(val accessToken: String, val refreshToken: String, val user: UserDto, val isNewUser: Boolean)
@Serializable data class TokenResponse(val accessToken: String, val refreshToken: String)
@Serializable data class MeResponse(val user: UserDto, val hasClub: Boolean)

fun User.toDto() = UserDto(id.toString(), authProvider.name, email, displayName, avatarUrl)
