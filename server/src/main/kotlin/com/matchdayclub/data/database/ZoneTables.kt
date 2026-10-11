package com.matchdayclub.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object ClubZoneTable : Table("club_zones") {
    val clubId = uuid("club_id"); val sport = varchar("sport", 16); val state = varchar("state", 12)
    val boughtAt = timestamp("bought_at"); val restoreEndsAt = timestamp("restore_ends_at").nullable()
    override val primaryKey = PrimaryKey(clubId, sport)
}
