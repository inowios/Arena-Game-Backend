package com.matchdayclub.engine.model

import kotlinx.serialization.Serializable

enum class Style { ATTACK, BALANCED, DEFEND }
enum class Tempo { HIGH, LOW }
enum class Plan { NONE, PRESSING, COUNTER, POSSESSION, LONG_BALL }

@Serializable
data class Tactics(val style: Style = Style.BALANCED, val tempo: Tempo = Tempo.LOW, val plan: Plan = Plan.NONE)

@Serializable
data class Lineup(
    val teamId: String,
    val starters: List<Athlete>,
    val bench: List<Athlete> = emptyList(),
    val tactics: Tactics = Tactics(),
)
