package com.matchdayclub.data.database

import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

object ClubTable : BaseTable("clubs") {
    val userId = uuid("user_id").references(UserTable.id)
    val name = varchar("name", 40)
    val primaryColor = char("primary_color", 7)
    val secondaryColor = char("secondary_color", 7)
    val emblemJson = text("emblem_json").nullable()
    val level = integer("level")
    val reputation = integer("reputation")
    val coins = long("coins")
    val gold = integer("gold")
    val timezone = varchar("timezone", 64)
    val academyNextAt = timestamp("academy_next_at").nullable()
    val medicalNextAt = timestamp("medical_next_at").nullable()
    val isBot = bool("is_bot")
    val division = integer("division")
    val tzBucket = integer("tz_bucket")
    val friendliesDate = date("friendlies_date").nullable()
    val friendliesPlayed = integer("friendlies_played")
    val loginDay = integer("login_day")
    val loginLast = date("login_last").nullable()
    val streak = integer("streak")
    val doubleIncomeUntil = timestamp("double_income_until").nullable()
    val doubleIncomeAdAt = timestamp("double_income_ad_at").nullable()
    val freeSpeedupDate = date("free_speedup_date").nullable()
    val builderSlots = integer("builder_slots")
    val seasonNo = integer("season_no")
}

object BuildingTable : BaseTable("buildings") {
    val clubId = uuid("club_id").references(ClubTable.id)
    val type = varchar("type", 32)
    val level = integer("level")
    val gridX = integer("grid_x")
    val gridY = integer("grid_y")
    val rotation = integer("rotation")
    val state = varchar("state", 16)
    val upgradeEndsAt = timestamp("upgrade_ends_at").nullable()
    val lastCollectedAt = timestamp("last_collected_at")
}
