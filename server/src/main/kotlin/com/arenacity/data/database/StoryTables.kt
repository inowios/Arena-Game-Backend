package com.arenacity.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** The club's story «Іскра» (V47): the chapter it works on and that chapter's goals («c1.o1=1,c1.o2=3»). */
object ClubStoryTable : Table("club_story") {
    val clubId = uuid("club_id").references(ClubTable.id)
    val chapter = integer("chapter")
    val progress = varchar("progress", 255)
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(clubId)
}
