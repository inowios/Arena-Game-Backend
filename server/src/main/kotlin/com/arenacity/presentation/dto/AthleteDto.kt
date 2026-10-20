package com.arenacity.presentation.dto

import com.arenacity.domain.model.Athlete
import kotlinx.serialization.Serializable
import java.time.Instant

/** An athlete (an academy pupil): the elite athlete of the season pass's last level still uses it. */
@Serializable data class AthleteDto(
    val id: String, val name: String, val sport: String, val position: String, val overall: Int,
    val attack: Int, val defense: Int, val physical: Int, val technique: Int, val mental: Int,
    val age: Int, val rarity: String, val fatigue: Int, val injured: Boolean, val injuredUntil: String?, val morale: Int,
    val potential: Int = 60,
    val xp: Int = 0, val xpNext: Int = 100,
    /** Technique levels by key (SHOOT, PASS, CROSS, FEINT, TACKLE, SLIDE, BLOCK, JUMP → 1..5) and points to spend on them. */
    val techniques: Map<String, Int> = emptyMap(),
    val skillPoints: Int = 0,
    /** The move he was born for (its steps cost a point less), if any. */
    val talent: String? = null,
    /** The card kind (CLASSIC … ARENA), the career experience and the card level it reaches within the kind's ceiling. */
    val cardKind: String = "CLASSIC", val xpTotal: Int = 0, val cardLevel: Int = 1,
)
fun Athlete.toDto(now: Instant) = AthleteDto(id.toString(), name, sport.name, position.name, overall, attack, defense, physical, technique, mental, age, rarity.name, currentFatigue(now), isInjured(now), injuredUntil?.toString(), morale, potential, xp, com.arenacity.domain.rules.Growth.XP_PER_POINT, techniques, skillPoints, talent, cardKind.name, xpTotal, com.arenacity.domain.model.CardKind.levelFor(xpTotal, cardKind))
