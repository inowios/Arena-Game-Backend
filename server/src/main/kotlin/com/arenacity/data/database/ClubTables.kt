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
    val doubleIncomeUntil = timestamp("double_income_until").nullable()
    val doubleIncomeAdAt = timestamp("double_income_ad_at").nullable()
    val freeSpeedupDate = date("free_speedup_date").nullable()
    val builderSlots = integer("builder_slots")
    val seasonNo = integer("season_no")
    val kit = varchar("kit", 40).nullable()
    val emblemFrame = varchar("emblem_frame", 40).nullable()
    val celebration = varchar("celebration", 40).nullable()
    val scoutSignedDate = date("scout_signed_date").nullable()
    val trophies = integer("trophies")
    val liveStreak = integer("live_streak")
    val marketSalt = integer("market_salt")
    val arenaChestWeek = integer("arena_chest_week")
    /** The land mask the buildings were last moved onto (V34); written only by [com.arenacity.domain.repository.ClubRepository.setLandMask] and on insert. */
    val landMask = varchar("land_mask", 40).nullable()
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
    val variant = varchar("variant", 40).nullable()
}

object ObstacleTable : BaseTable("obstacles") {
    val clubId = uuid("club_id").references(ClubTable.id)
    val kind = varchar("kind", 16)
    val gridX = integer("grid_x")
    val gridY = integer("grid_y")
    val state = varchar("state", 16)
    val clearEndsAt = timestamp("clear_ends_at").nullable()
    val rewardGold = integer("reward_gold")
}

/** The clubs table's obstacle clock alone (kept out of [ClubTable] so club updates never overwrite it). */
object ClubObstacleClock : org.jetbrains.exposed.sql.Table("clubs") {
    val id = uuid("id")
    val spawnedAt = timestamp("obstacles_spawned_at").nullable()
}

/** Saved layouts: [data] = "id,x,y,rotation;…" (one entry per building). */
object ClubLayoutTable : org.jetbrains.exposed.sql.Table("club_layouts") {
    val clubId = uuid("club_id")
    val slot = integer("slot")
    val name = varchar("name", 32)
    val data = text("data")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(clubId, slot)
}

/** Claimed road steps (trophy road, club-level road). */
object ClubRoadClaimTable : org.jetbrains.exposed.sql.Table("club_road_claims") {
    val clubId = uuid("club_id")
    val road = varchar("road", 16)
    val step = integer("step")
    val claimedAt = timestamp("claimed_at")
    override val primaryKey = PrimaryKey(clubId, road, step)
}
