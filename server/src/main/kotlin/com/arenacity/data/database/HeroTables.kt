package com.arenacity.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp

/** The heroes a club owns (V45): the hero ids are rules.HeroCatalog's ("p001"…). */
object ClubHeroTable : Table("club_heroes") {
    val clubId = uuid("club_id").references(ClubTable.id)
    val heroId = varchar("hero_id", 8)
    val level = integer("level")
    val xp = integer("xp")
    val xpDay = date("xp_day").nullable()
    val xpToday = integer("xp_today")
    val heroSource = varchar("source", 12)
    val unlockedAt = timestamp("unlocked_at")
    override val primaryKey = PrimaryKey(clubId, heroId)
}
