package com.matchdayclub.data.database

object UserTable : BaseTable("users") {
    val authProvider = varchar("auth_provider", 10)
    val providerId = varchar("provider_id", 255)
    val email = varchar("email", 255).nullable()
    val displayName = varchar("display_name", 80).nullable()
    val avatarUrl = text("avatar_url").nullable()
    val isActive = bool("is_active")
    val locale = varchar("locale", 8).nullable()
}
