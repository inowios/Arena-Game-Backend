package com.arenacity.engine.model

import kotlinx.serialization.Serializable

/**
 * GOAL is any score (football goal, basket: [MatchEvent.value] = 2 or 3). PERIOD closes a quarter (detail = number).
 * Tennis: GAME (detail = games in the set after it, value = set number), SET (detail = "6-4"), BREAK marks a GAME won on return.
 */
enum class EventType { KICKOFF, CHANCE, SHOT, GOAL, SAVE, MISS, INJURY, SUBSTITUTION, HALFTIME, FULLTIME, YELLOW_CARD, PERIOD, OVERTIME, GAME, SET, BREAK }

@Serializable
data class MatchEvent(val minute: Int, val type: EventType, val team: String, val athleteId: String? = null, val detail: String? = null, val value: Int? = null)

@Serializable
data class Score(val home: Int, val away: Int)

@Serializable
data class MatchResult(
    val sport: String,
    val seed: Long,
    val homeTeam: String,
    val awayTeam: String,
    val score: Score,
    val events: List<MatchEvent>,
    val homePossession: Int,
    val homeShots: Int,
    val awayShots: Int,
    /** Football halves, basketball quarters (+ overtimes), tennis sets (games). */
    val periods: List<Score> = emptyList(),
    val overtime: Boolean = false,
)
