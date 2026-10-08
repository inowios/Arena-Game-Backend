package com.matchdayclub.engine.model

import kotlinx.serialization.Serializable

enum class EventType { KICKOFF, CHANCE, SHOT, GOAL, SAVE, MISS, INJURY, SUBSTITUTION, HALFTIME, FULLTIME, YELLOW_CARD }

@Serializable
data class MatchEvent(val minute: Int, val type: EventType, val team: String, val athleteId: String? = null, val detail: String? = null)

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
)
