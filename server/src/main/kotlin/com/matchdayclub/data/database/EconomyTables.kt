package com.matchdayclub.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

object SeasonPassTable : Table("season_pass") {
    val clubId = uuid("club_id"); val season = integer("season"); val xp = integer("xp"); val premium = bool("premium")
    val claimedFree = integer("claimed_free"); val claimedPremium = integer("claimed_premium")
    override val primaryKey = PrimaryKey(clubId, season)
}

object DailyTaskTable : Table("daily_tasks") {
    val clubId = uuid("club_id"); val day = date("day"); val task = varchar("task", 24); val progress = integer("progress"); val claimed = bool("claimed")
    override val primaryKey = PrimaryKey(clubId, day, task)
}

object PurchaseTable : Table("purchases") {
    val id = uuid("id"); val clubId = uuid("club_id"); val store = varchar("store", 8); val productId = varchar("product_id", 64); val externalId = varchar("external_id", 255)
    val status = varchar("status", 12); val grantedGold = integer("granted_gold"); val grantedCoins = long("granted_coins"); val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object PushTokenTable : BaseTable("push_tokens") {
    val userId = uuid("user_id"); val token = text("token"); val platform = varchar("platform", 10)
}
