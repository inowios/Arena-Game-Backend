package com.matchdayclub.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object AthleteTable : BaseTable("athletes") {
    val clubId = uuid("club_id").references(ClubTable.id)
    val name = varchar("name", 60)
    val sport = varchar("sport", 16)
    val position = varchar("position", 8)
    val attack = integer("attack"); val defense = integer("defense"); val physical = integer("physical"); val technique = integer("technique"); val mental = integer("mental")
    val age = integer("age")
    val rarity = varchar("rarity", 8)
    val fatigue = integer("fatigue")
    val fatigueAt = timestamp("fatigue_at")
    val injuredUntil = timestamp("injured_until").nullable()
    val morale = integer("morale")
    val potential = integer("potential")
    val retiredAt = timestamp("retired_at").nullable()
    val xp = integer("xp")
    val techniques = text("techniques")
    val skillPoints = integer("skill_points")
    val xpTotal = integer("xp_total")
}

object TrainingTable : Table("trainings") {
    val id = uuid("id")
    val clubId = uuid("club_id").references(ClubTable.id)
    val athleteId = uuid("athlete_id").references(AthleteTable.id)
    val attribute = varchar("attribute", 12)
    val delta = integer("delta")
    val endsAt = timestamp("ends_at")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object LineupTable : Table("lineups") {
    val clubId = uuid("club_id").references(ClubTable.id)
    val sport = varchar("sport", 16)
    val startersJson = text("starters_json")
    val benchJson = text("bench_json")
    val tacticsJson = text("tactics_json")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(clubId, sport)
}
