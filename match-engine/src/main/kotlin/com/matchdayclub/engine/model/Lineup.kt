package com.matchdayclub.engine.model

import kotlinx.serialization.Serializable

enum class Style { ATTACK, BALANCED, DEFEND }
enum class Tempo { HIGH, LOW }
enum class Plan { NONE, PRESSING, COUNTER, POSSESSION, LONG_BALL }

@Serializable
/** [formation] is the football shape the client lays the starters out in ("4-4-2" … "5-3-2"); other sports ignore it. */
data class Tactics(val style: Style = Style.BALANCED, val tempo: Tempo = Tempo.LOW, val plan: Plan = Plan.NONE, val formation: String = "4-4-2")

@Serializable
data class Lineup(
    val teamId: String,
    val starters: List<Athlete>,
    val bench: List<Athlete> = emptyList(),
    val tactics: Tactics = Tactics(),
)
