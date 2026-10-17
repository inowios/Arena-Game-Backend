package com.arenacity.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object AllianceTable : Table("alliances") {
    val id = uuid("id"); val name = varchar("name", 24); val tag = varchar("tag", 5); val description = varchar("description", 160)
    val emblemJson = text("emblem_json").nullable(); val open = bool("open"); val minTrophies = integer("min_trophies"); val createdBy = uuid("created_by")
    val createdAt = timestamp("created_at"); val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}
object AllianceMemberTable : Table("alliance_members") {
    val allianceId = uuid("alliance_id"); val clubId = uuid("club_id"); val role = varchar("role", 12); val joinedAt = timestamp("joined_at"); val helpsGiven = integer("helps_given")
    override val primaryKey = PrimaryKey(allianceId, clubId)
}
object AllianceRequestTable : Table("alliance_requests") {
    val allianceId = uuid("alliance_id"); val clubId = uuid("club_id"); val message = varchar("message", 120); val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(allianceId, clubId)
}
object AllianceWeekendTable : Table("alliance_weekend") {
    val week = integer("week"); val allianceId = uuid("alliance_id"); val clubId = uuid("club_id"); val wins = integer("wins")
    override val primaryKey = PrimaryKey(week, allianceId, clubId)
}
object AllianceWeekendClaimTable : Table("alliance_weekend_claims") {
    val week = integer("week"); val clubId = uuid("club_id"); val claimedAt = timestamp("claimed_at")
    override val primaryKey = PrimaryKey(week, clubId)
}
object ClubLikeTable : Table("club_likes") {
    val clubId = uuid("club_id"); val likerId = uuid("liker_id"); val week = integer("week"); val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(clubId, likerId, week)
}
