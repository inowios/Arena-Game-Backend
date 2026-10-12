package com.arenacity.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

object LeagueTable : BaseTable("leagues") {
    val kind = varchar("kind", 8)
    val name = varchar("name", 40)
    val code = varchar("code", 6).nullable()
    val division = integer("division")
    val tzBucket = integer("tz_bucket")
    val timezone = varchar("timezone", 64)
    val matchdayHour = integer("matchday_hour")
    val seasonNumber = integer("season_number")
    val seasonStart = date("season_start")
    val currentRound = integer("current_round")
    val createdBy = uuid("created_by").nullable()
    val status = varchar("status", 10)
}

object LeagueMemberTable : Table("league_members") {
    val leagueId = uuid("league_id").references(LeagueTable.id)
    val clubId = uuid("club_id").references(ClubTable.id)
    val joinedAt = timestamp("joined_at")
    override val primaryKey = PrimaryKey(leagueId, clubId)
}

object FixtureTable : Table("fixtures") {
    val id = uuid("id")
    val leagueId = uuid("league_id").nullable()
    val kind = varchar("kind", 10)
    val seasonNumber = integer("season_number")
    val round = integer("round")
    val scheduledAt = timestamp("scheduled_at")
    val homeClubId = uuid("home_club_id")
    val awayClubId = uuid("away_club_id")
    val status = varchar("status", 10)
    val homeScore = integer("home_score").nullable()
    val awayScore = integer("away_score").nullable()
    val seed = long("seed")
    val resultJson = text("result_json").nullable()
    val playedAt = timestamp("played_at").nullable()
    val createdAt = timestamp("created_at")
    val homePoints = integer("home_points").nullable()
    val awayPoints = integer("away_points").nullable()
    override val primaryKey = PrimaryKey(id)
}
