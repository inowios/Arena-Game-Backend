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
    val trainingEndsAt = timestamp("training_ends_at").nullable()
    val trainingXp = integer("training_xp")
    override val primaryKey = PrimaryKey(clubId, heroId)
}

/** The club's «Стрілка» lineup (V46): hero ids comma-separated, in order; keeper null = an academy pupil. */
object ClubHeroLineupTable : Table("club_hero_lineups") {
    val clubId = uuid("club_id").references(ClubTable.id)
    val field = varchar("field", 64)
    val keeper = varchar("keeper", 8).nullable()
    val bench = varchar("bench", 64)
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(clubId)
}
