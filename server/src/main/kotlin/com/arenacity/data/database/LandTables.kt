package com.arenacity.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** What the land migration moved onto which mask (V34; a removed road or fence tile's level and refund: V35). */
object RehomeLogTable : Table("rehome_log") {
    val id = long("id").autoIncrement()
    val clubId = uuid("club_id")
    val itemId = uuid("item_id")
    val kind = varchar("kind", 16)
    val type = varchar("type", 32)
    val oldX = integer("old_x"); val oldY = integer("old_y"); val oldRotation = integer("old_rotation")
    val newX = integer("new_x").nullable(); val newY = integer("new_y").nullable(); val newRotation = integer("new_rotation")
    val gold = integer("gold")
    val level = integer("level")
    val coins = long("coins")
    val maskId = varchar("mask_id", 40)
    val at = timestamp("at")
    override val primaryKey = PrimaryKey(id)
}
