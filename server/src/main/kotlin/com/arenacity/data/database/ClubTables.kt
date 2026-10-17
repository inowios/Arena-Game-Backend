package com.arenacity.data.database

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
    val seasonNo = integer("season_no")
    val kit = varchar("kit", 40).nullable()
    val emblemFrame = varchar("emblem_frame", 40).nullable()
    val celebration = varchar("celebration", 40).nullable()
    val scoutSignedDate = date("scout_signed_date").nullable()
    val trophies = integer("trophies")
    val liveStreak = integer("live_streak")
    val marketSalt = integer("market_salt")
    val arenaChestWeek = integer("arena_chest_week")
    val energy = integer("energy")
    val energyAt = timestamp("energy_at").nullable()
    val offerDay = date("offer_day").nullable()
    /** When the owner was last seen (V42); written only by [com.arenacity.domain.repository.ClubRepository.touchSeen]. */
    val lastSeenAt = timestamp("last_seen_at").nullable()
}

object MarketPurchaseTable : org.jetbrains.exposed.sql.Table("market_purchases") {
    val clubId = uuid("club_id").references(ClubTable.id)
    val period = long("period")
    val salt = integer("salt")
    val idx = integer("idx")
    val athleteId = uuid("athlete_id")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(clubId, period, salt, idx)
}

/**
 * The main-island spots a club opened (V48, rules.MapSpots). The builder's old columns (land_mask, builder_slots, the ads'
 * double income and free speed-up) and tables (buildings, obstacles, club_layouts, rehome_log) are still in the database
 * but no longer mapped: dropping them is a later step.
 */
object ClubSpotTable : org.jetbrains.exposed.sql.Table("club_spots") {
    val clubId = uuid("club_id").references(ClubTable.id)
    val spot = varchar("spot", 32)
    val unlockedAt = timestamp("unlocked_at")
    override val primaryKey = PrimaryKey(clubId, spot)
}

/** Claimed road steps (trophy road, club-level road). */
object ClubRoadClaimTable : org.jetbrains.exposed.sql.Table("club_road_claims") {
    val clubId = uuid("club_id")
    val road = varchar("road", 16)
    val step = integer("step")
    val claimedAt = timestamp("claimed_at")
    override val primaryKey = PrimaryKey(clubId, road, step)
}
