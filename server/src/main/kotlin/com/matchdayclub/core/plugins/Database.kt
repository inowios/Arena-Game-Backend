package com.matchdayclub.core.plugins

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import javax.sql.DataSource

fun Application.configureDatabase() {
    val cfg = environment.config
    val hikari = HikariConfig().apply {
        driverClassName = "org.postgresql.Driver"
        jdbcUrl = cfg.property("database.jdbcUrl").getString()
        username = cfg.property("database.user").getString()
        password = cfg.property("database.password").getString()
        maximumPoolSize = 10
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_READ_COMMITTED"   // row locks (FOR UPDATE) serialise club mutations
        validate()
    }
    val dataSource = HikariDataSource(hikari)
    runMigrations(dataSource)
    Database.connect(dataSource, databaseConfig = DatabaseConfig { defaultMaxAttempts = 1 })
    environment.log.info("Database configured")
}

fun Application.runMigrations(dataSource: DataSource) {
    val flyway = Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .load()
    val result = flyway.migrate()
    environment.log.info("Applied ${result.migrationsExecuted} migrations")
}
