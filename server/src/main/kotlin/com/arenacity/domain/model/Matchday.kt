package com.arenacity.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The one sport a fixture is played in (the Arena v4, 3 v 3; kept as an enum for the client's `sections` field). */
enum class Sport { FOOTBALL }

/** A hero in a played fixture: the catalog id, its level at the time, the stamina it ended on (0 = taken out). */
@Serializable
data class MatchHero(val id: String, val level: Int, val stamina: Int)

/**
 * One line of the short match log: [kind] = cast (a skill used: [hero], [skill] its name, [skillId], [target] the hero it was
 * aimed at, [damage] = all the damage it dealt), tick (an ongoing skill hit again), out (a hero taken out), end (the match
 * over: [text]). [side] = HOME | AWAY, the side that acted.
 */
@Serializable
data class MatchLine(val turn: Int, val side: String, val kind: String, val hero: String? = null, val skill: String? = null, val skillId: String? = null,
                     val target: String? = null, val damage: Int = 0, val text: String? = null)

/**
 * Stored in fixtures.result_json (v3): the Arena v4 match the fixture was played as — the home triple against the away
 * triple, SmartAi on both sides, the seed from the fixture. [homeWon] (no draws), [turns] played, [first] = the side that
 * moved first (HOME | AWAY), the triples as they ended, the short log. Legacy rows (v2 sections / v1 football) still
 * [parse]: they keep the result and come without heroes or a log.
 */
@Serializable
data class MatchdayResult(val v: Int = 3, val homeWon: Boolean, val turns: Int = 0, val first: String = "HOME", val home: List<MatchHero> = emptyList(),
                          val away: List<MatchHero> = emptyList(), val events: List<MatchLine> = emptyList()) {
    /** The table's view: a win is 1:0 and 3 points, a loss 0:1 and 0. */
    val homeScore: Int get() = if (homeWon) 1 else 0
    val awayScore: Int get() = if (homeWon) 0 else 1
    val homePoints: Int get() = if (homeWon) 3 else 0
    val awayPoints: Int get() = if (homeWon) 0 else 3
    /** The rival heroes a side took out. */
    val homeKnockouts: Int get() = away.count { it.stamina <= 0 }
    val awayKnockouts: Int get() = home.count { it.stamina <= 0 }

    fun toJson(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        /** A v3 row as it is; an older row (v2 with sections, or a bare v1 football match) as a result without heroes or a log. */
        fun parse(text: String): MatchdayResult {
            val root = json.parseToJsonElement(text).jsonObject
            if (root.containsKey("homeWon")) return json.decodeFromString<MatchdayResult>(text)
            return legacy(root)
        }
        private fun legacy(root: JsonObject): MatchdayResult {
            val sections = root["sections"]?.jsonArray
            val (h, a) = if (sections != null) {
                sections.sumOf { it.jsonObject["homePoints"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 } to sections.sumOf { it.jsonObject["awayPoints"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0 }
            } else {
                val score = root["score"]?.jsonObject
                (score?.get("home")?.jsonPrimitive?.content?.toIntOrNull() ?: 0) to (score?.get("away")?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
            }
            return MatchdayResult(v = 0, homeWon = h >= a)
        }
    }
}
