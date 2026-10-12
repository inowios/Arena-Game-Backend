package com.arenacity.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object LeagueMessageTable : Table("league_messages") {
    val id = uuid("id"); val leagueId = uuid("league_id"); val clubId = uuid("club_id").nullable(); val kind = varchar("kind", 10)
    val text = varchar("text", 400); val deleted = bool("deleted"); val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object MessageReportTable : Table("message_reports") {
    val id = uuid("id"); val messageId = uuid("message_id"); val reporterClubId = uuid("reporter_club_id"); val reason = varchar("reason", 40); val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ClubBlockTable : Table("club_blocks") {
    val clubId = uuid("club_id"); val blockedClubId = uuid("blocked_club_id"); val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(clubId, blockedClubId)
}

object PrivateLeagueSeasonTable : Table("private_league_seasons") {
    val id = uuid("id"); val leagueId = uuid("league_id"); val seasonNumber = integer("season_number"); val winnerClubId = uuid("winner_club_id").nullable()
    val tableJson = text("table_json"); val endedAt = timestamp("ended_at")
    override val primaryKey = PrimaryKey(id)
}

object ChallengeTable : Table("challenges") {
    val id = uuid("id"); val code = varchar("code", 10); val challengerClubId = uuid("challenger_club_id"); val acceptedByClubId = uuid("accepted_by_club_id").nullable()
    val fixtureId = uuid("fixture_id").nullable(); val status = varchar("status", 10); val expiresAt = timestamp("expires_at"); val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object InviteTable : Table("invites") {
    val id = uuid("id"); val inviterClubId = uuid("inviter_club_id"); val inviteeClubId = uuid("invitee_club_id"); val kind = varchar("kind", 10)
    val code = varchar("code", 10); val newPlayer = bool("new_player"); val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
