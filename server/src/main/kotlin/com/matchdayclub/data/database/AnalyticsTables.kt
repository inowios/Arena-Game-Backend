package com.matchdayclub.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

object UserDayTable : Table("user_days") {
    val userId = uuid("user_id"); val day = date("day")
    override val primaryKey = PrimaryKey(userId, day)
}

object AnalyticsEventTable : Table("analytics_events") {
    val id = long("id").autoIncrement(); val userId = uuid("user_id").nullable(); val name = varchar("name", 40); val props = text("props").nullable()
    val platform = varchar("platform", 10).nullable(); val appVersion = varchar("app_version", 20).nullable(); val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
