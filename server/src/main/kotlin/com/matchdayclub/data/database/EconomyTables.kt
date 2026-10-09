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

object ClubItemTable : Table("club_items") {
    val clubId = uuid("club_id"); val itemId = varchar("item_id", 40); val itemSource = varchar("source", 16); val acquiredAt = timestamp("acquired_at")
    override val primaryKey = PrimaryKey(clubId, itemId)
}

object ClubCounterTable : Table("club_counters") {
    val clubId = uuid("club_id"); val metric = varchar("metric", 24); val value = long("value")
    override val primaryKey = PrimaryKey(clubId, metric)
}

object ClubAchievementTable : Table("club_achievements") {
    val clubId = uuid("club_id"); val achievement = varchar("achievement", 32); val tier = integer("tier"); val claimedAt = timestamp("claimed_at")
    override val primaryKey = PrimaryKey(clubId, achievement)
}

object AdRewardTable : Table("ad_rewards") {
    val transactionId = varchar("transaction_id", 128); val clubId = uuid("club_id"); val placement = varchar("placement", 24); val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(transactionId)
}
