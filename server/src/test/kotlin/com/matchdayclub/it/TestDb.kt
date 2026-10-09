package com.matchdayclub.it

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

/**
 * Integration tests run against a real PostgreSQL when TEST_DATABASE_URL is set (CI service container or a local database),
 * e.g. TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/matchdayclub_test TEST_DATABASE_USER=postgres ./gradlew :server:test
 * The schema is wiped and migrated from scratch for every test class.
 */
object TestDb {
    val url: String? = System.getenv("TEST_DATABASE_URL")
    val user: String = System.getenv("TEST_DATABASE_USER") ?: "postgres"
    val password: String = System.getenv("TEST_DATABASE_PASSWORD") ?: ""

    fun fresh(): HikariDataSource {
        val ds = HikariDataSource(HikariConfig().apply {
            driverClassName = "org.postgresql.Driver"; jdbcUrl = url; username = user; password = this@TestDb.password
            maximumPoolSize = 12; isAutoCommit = false; transactionIsolation = "TRANSACTION_READ_COMMITTED"
        })
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false).load().apply { clean(); migrate() }
        Database.connect(ds)
        return ds
    }

    /** Application config for `testApplication { environment { config = TestDb.appConfig() } }` against the same database. */
    fun appConfig(extra: Map<String, String> = emptyMap()) = MapApplicationConfig(
        "ktor.development" to "true",
        "jwt.secret" to "it-secret", "jwt.issuer" to "it-issuer", "jwt.audience" to "it-aud", "jwt.realm" to "it",
        "jwt.accessTokenExpiryMs" to "3600000", "jwt.refreshTokenExpiryMs" to "2592000000",
        "database.jdbcUrl" to url!!, "database.user" to user, "database.password" to password,
        "oauth.google.clientIds" to "", "oauth.apple.bundleId" to "com.inowios.matchdayclub",
        "cors.allowedHosts" to "", "matchday.schedulerEnabled" to "false", "iap.devMode" to "true",
        *extra.toList().toTypedArray(),
    )
}
