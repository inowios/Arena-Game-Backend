package com.matchdayclub.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object GameEventTable : Table("game_events") {
    val id = varchar("id", 64); val key = varchar("key", 32); val startsAt = timestamp("starts_at"); val endsAt = timestamp("ends_at")
    val buildTimePct = integer("build_time_pct"); val incomePct = integer("income_pct"); val obstacleGoldPct = integer("obstacle_gold_pct")
    val goal = varchar("goal", 32).nullable(); val goalTarget = integer("goal_target"); val rewardCoins = long("reward_coins"); val rewardGold = integer("reward_gold"); val rewardItem = varchar("reward_item", 40).nullable()
    override val primaryKey = PrimaryKey(id)
}
object EventProgressTable : Table("event_progress") {
    val eventId = varchar("event_id", 64); val clubId = uuid("club_id"); val count = integer("count"); val claimedAt = timestamp("claimed_at").nullable()
    override val primaryKey = PrimaryKey(eventId, clubId)
}
object DevMailTable : Table("dev_mail") {
    val id = uuid("id"); val title = varchar("title", 80); val body = varchar("body", 1000); val coins = long("coins"); val gold = integer("gold"); val item = varchar("item", 40).nullable()
    val clubId = uuid("club_id").nullable(); val createdAt = timestamp("created_at"); val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(id)
}
object DevMailClaimTable : Table("dev_mail_claims") {
    val mailId = uuid("mail_id"); val clubId = uuid("club_id"); val claimedAt = timestamp("claimed_at")
    override val primaryKey = PrimaryKey(mailId, clubId)
}
object OfferPurchaseTable : Table("offer_purchases") {
    val clubId = uuid("club_id"); val day = integer("day"); val offerId = varchar("offer_id", 32); val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(clubId, day, offerId)
}
