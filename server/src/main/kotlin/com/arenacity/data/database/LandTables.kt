package com.arenacity.data.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object LandParcelTable : Table("land_parcels") {
    val clubId = uuid("club_id"); val col = integer("col"); val row = integer("row"); val boughtAt = timestamp("bought_at")
    override val primaryKey = PrimaryKey(clubId, col, row)
}
