package com.matchdayclub.domain.model

import com.matchdayclub.engine.model.MatchResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One sport section of a fixture. [walkover] = "HOME"/"AWAY" when only that club fields the section (no simulation). */
@Serializable
data class SectionResult(
    val sport: String,
    val homeScore: Int,
    val awayScore: Int,
    val homePoints: Int,
    val awayPoints: Int,
    val walkover: String? = null,
    val result: MatchResult? = null,
)

/** Stored in fixtures.result_json (v2). Legacy rows hold a bare football [MatchResult]; [parse] reads both. */
@Serializable
data class MatchdayResult(val v: Int = 2, val sections: List<SectionResult>) {
    val homePoints get() = sections.sumOf { it.homePoints }
    val awayPoints get() = sections.sumOf { it.awayPoints }
    fun section(sport: Sport) = sections.firstOrNull { it.sport == sport.name }
    /** Football result (always played) for clients that show a single match. */
    val primary: MatchResult? get() = section(Sport.FOOTBALL)?.result ?: sections.firstNotNullOfOrNull { it.result }

    fun toJson(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun parse(text: String): MatchdayResult =
            runCatching { json.decodeFromString<MatchdayResult>(text) }.getOrNull()
                ?: json.decodeFromString<MatchResult>(text).let { r ->
                    val hp = if (r.score.home > r.score.away) 3 else if (r.score.home == r.score.away) 1 else 0
                    val ap = if (r.score.away > r.score.home) 3 else if (r.score.home == r.score.away) 1 else 0
                    MatchdayResult(2, listOf(SectionResult(Sport.FOOTBALL.name, r.score.home, r.score.away, hp, ap, null, r)))
                }
    }
}
