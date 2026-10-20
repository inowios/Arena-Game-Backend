package com.arenacity.it

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

/**
 * Integration tests run against a real PostgreSQL when TEST_DATABASE_URL is set (CI service container or a local database),
 * e.g. TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/arenacity_test TEST_DATABASE_USER=postgres ./gradlew :server:test
 * The schema is wiped and migrated from scratch for every test class.
 */
object TestDb {
    val url: String? = System.getenv("TEST_DATABASE_URL")
    val user: String = System.getenv("TEST_DATABASE_USER") ?: "postgres"
    val password: String = System.getenv("TEST_DATABASE_PASSWORD") ?: ""

    private var last: HikariDataSource? = null

    /**
     * Wipes and migrates the schema ([target]: up to that version only, e.g. "35" to test the next migration on old
     * data — [migrate] then runs the rest); the previous test's pool is closed so connections never pile up.
     */
    fun fresh(target: String? = null): HikariDataSource {
        last?.takeUnless { it.isClosed }?.close()
        val ds = HikariDataSource(HikariConfig().apply {
            driverClassName = "org.postgresql.Driver"; jdbcUrl = url; username = user; password = this@TestDb.password
            maximumPoolSize = 12; isAutoCommit = false; transactionIsolation = "TRANSACTION_READ_COMMITTED"
        })
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false).apply { if (target != null) target(target) }.load().apply { clean(); migrate() }
        if (target != null) laterColumns(ds)
        // explicit default: earlier test classes' pools are closed by now
        org.jetbrains.exposed.sql.transactions.TransactionManager.defaultDatabase = Database.connect(ds)
        last = ds
        return ds
    }

    /** Runs the migrations [fresh] left out (all of them up to the latest). */
    fun migrate(ds: HikariDataSource, target: String? = null) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").apply { if (target != null) target(target) }.load().migrate()
        if (target != null) laterColumns(ds)
    }

    /**
     * A schema migrated only up to an old version still has to be read by today's repositories (a migration test runs the
     * current code between the steps): the columns later migrations add with defaults (ADD COLUMN IF NOT EXISTS there, so
     * running them afterwards changes nothing) are added here.
     */
    private fun laterColumns(ds: HikariDataSource) = ds.connection.use { c ->
        c.createStatement().execute("""
            ALTER TABLE clubs ADD COLUMN IF NOT EXISTS energy INT NOT NULL DEFAULT 30;
            ALTER TABLE clubs ADD COLUMN IF NOT EXISTS energy_at TIMESTAMPTZ NULL;
            ALTER TABLE clubs ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ NULL;
        """.trimIndent())
        c.commit()
    }

    /** Application config for `testApplication { environment { config = TestDb.appConfig() } }` against the same database. */
    fun appConfig(extra: Map<String, String> = emptyMap()) = MapApplicationConfig(
        "ktor.development" to "true",
        "jwt.secret" to "it-secret", "jwt.issuer" to "it-issuer", "jwt.audience" to "it-aud", "jwt.realm" to "it",
        "jwt.accessTokenExpiryMs" to "3600000", "jwt.refreshTokenExpiryMs" to "2592000000",
        "database.jdbcUrl" to url!!, "database.user" to user, "database.password" to password,
        "oauth.google.clientIds" to "", "oauth.apple.bundleId" to "com.inowios.arenacity",
        "cors.allowedHosts" to "", "matchday.schedulerEnabled" to "false", "iap.devMode" to "true",
        *extra.toList().toTypedArray(),
    )
}
