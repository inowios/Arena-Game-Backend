package com.matchdayclub.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** UUID id + created/updated timestamps. Repositories set the timestamps explicitly on insert/update. */
abstract class BaseTable(name: String) : Table(name) {
    val id = uuid("id")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}
