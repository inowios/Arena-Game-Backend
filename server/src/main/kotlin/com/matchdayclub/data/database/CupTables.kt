package com.matchdayclub.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

object CupTable : BaseTable("cups") {
    val tzBucket = integer("tz_bucket"); val weekStart = date("week_start"); val tier = integer("tier"); val division = integer("division")
    val currentRound = integer("current_round"); val status = varchar("status", 10); val winnerClubId = uuid("winner_club_id").nullable()
    val timezone = varchar("timezone", 64).nullable()
}

object CupEntryTable : Table("cup_entries") {
    val weekStart = date("week_start"); val clubId = uuid("club_id"); val cupId = uuid("cup_id"); val tzBucket = integer("tz_bucket"); val seed = integer("seed")
    val outRound = integer("out_round").nullable(); val prizeCoins = long("prize_coins"); val prizeGold = integer("prize_gold"); val prizeReputation = integer("prize_reputation")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(weekStart, clubId)
}

object CupTieTable : Table("cup_ties") {
    val cupId = uuid("cup_id"); val round = integer("round"); val slot = integer("slot"); val homeClubId = uuid("home_club_id"); val awayClubId = uuid("away_club_id")
    val fixtureId = uuid("fixture_id"); val winnerClubId = uuid("winner_club_id").nullable()
    override val primaryKey = PrimaryKey(cupId, round, slot)
}
