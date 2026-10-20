package com.arenacity.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** The Arena matches handed out (V59): the token the result must carry, its stake and story chapter, its result. */
object ArenaMatchTable : Table("arena_matches") {
    val id = uuid("id")
    val clubId = uuid("club_id").references(ClubTable.id)
    val stake = integer("stake")
    val story = integer("story").nullable()
    val status = varchar("status", 12)
    val startedAt = timestamp("started_at")
    val finishedAt = timestamp("finished_at").nullable()
    val won = bool("won").nullable()
    override val primaryKey = PrimaryKey(id)
}
