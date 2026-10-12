package com.arenacity.data.database

import org.jetbrains.exposed.sql.javatime.timestamp

object LiveMatchTable : BaseTable("live_matches") {
    val clubId = uuid("club_id").references(ClubTable.id)
    val opponentId = uuid("opponent_id").references(ClubTable.id)
    val seed = long("seed")
    val status = varchar("status", 16)
    val startersJson = text("starters_json")
    val oppStartersJson = text("opp_starters_json")
    val tempo = double("tempo")
    val myOverall = integer("my_overall")
    val oppOverall = integer("opp_overall")
    val homeScore = integer("home_score").nullable()
    val awayScore = integer("away_score").nullable()
    val resultJson = text("result_json").nullable()
    val trophiesDelta = integer("trophies_delta").nullable()
    val finishedAt = timestamp("finished_at").nullable()
}
