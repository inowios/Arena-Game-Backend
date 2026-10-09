package com.matchdayclub.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object ClubSeasonTable : Table("club_seasons") {
    val id = uuid("id"); val clubId = uuid("club_id"); val leagueId = uuid("league_id"); val seasonNo = integer("season_no")
    val division = integer("division"); val position = integer("position")
    val played = integer("played"); val won = integer("won"); val drawn = integer("drawn"); val lost = integer("lost")
    val gf = integer("gf"); val ga = integer("ga"); val points = integer("points")
    val movement = varchar("movement", 10); val newDivision = integer("new_division")
    val prizeCoins = long("prize_coins"); val prizeGold = integer("prize_gold"); val prizeReputation = integer("prize_reputation")
    val finishedAt = timestamp("finished_at")
    override val primaryKey = PrimaryKey(id)
}

object TrophyTable : Table("trophies") {
    val id = uuid("id"); val clubId = uuid("club_id"); val kind = varchar("kind", 20); val division = integer("division"); val seasonNo = integer("season_no")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
