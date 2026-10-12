package com.arenacity.data.repository

import com.arenacity.common.extension.dbQuery
import com.arenacity.data.database.UserTable
import com.arenacity.domain.model.AuthProvider
import com.arenacity.domain.model.User
import com.arenacity.domain.repository.UserRepository
import org.jetbrains.exposed.sql.*
import java.util.UUID

class UserRepositoryImpl : UserRepository {
    override suspend fun findById(id: UUID): User? = dbQuery { UserTable.selectAll().where { UserTable.id eq id }.singleOrNull()?.toUser() }

    override suspend fun findByProvider(provider: AuthProvider, providerId: String): User? = dbQuery {
        UserTable.selectAll().where { (UserTable.authProvider eq provider.name) and (UserTable.providerId eq providerId) }.singleOrNull()?.toUser()
    }

    override suspend fun findByEmail(email: String): User? = dbQuery { UserTable.selectAll().where { UserTable.email eq email }.firstOrNull()?.toUser() }

    override suspend fun create(user: User): User = dbQuery {
        UserTable.insert {
            it[id] = user.id; it[authProvider] = user.authProvider.name; it[providerId] = user.providerId; it[email] = user.email
            it[displayName] = user.displayName; it[avatarUrl] = user.avatarUrl; it[isActive] = user.isActive; it[locale] = user.locale
            it[createdAt] = user.createdAt; it[updatedAt] = user.updatedAt
        }
        user
    }

    override suspend fun update(user: User): User = dbQuery {
        UserTable.update({ UserTable.id eq user.id }) {
            it[authProvider] = user.authProvider.name; it[providerId] = user.providerId; it[email] = user.email
            it[displayName] = user.displayName; it[avatarUrl] = user.avatarUrl; it[isActive] = user.isActive; it[locale] = user.locale; it[updatedAt] = user.updatedAt
        }
        user
    }

    private fun ResultRow.toUser() = User(
        id = this[UserTable.id], authProvider = AuthProvider.valueOf(this[UserTable.authProvider]), providerId = this[UserTable.providerId],
        email = this[UserTable.email], displayName = this[UserTable.displayName], avatarUrl = this[UserTable.avatarUrl],
        isActive = this[UserTable.isActive], createdAt = this[UserTable.createdAt], updatedAt = this[UserTable.updatedAt], locale = this[UserTable.locale],
    )

    override suspend fun locales(ids: Collection<UUID>): Map<UUID, String?> = if (ids.isEmpty()) emptyMap() else dbQuery {
        UserTable.select(UserTable.id, UserTable.locale).where { UserTable.id inList ids }.associate { it[UserTable.id] to it[UserTable.locale] }
    }
}
