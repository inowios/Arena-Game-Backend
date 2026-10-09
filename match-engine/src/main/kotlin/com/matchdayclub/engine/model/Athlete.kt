package com.matchdayclub.engine.model

import kotlinx.serialization.Serializable

/**
 * Roles. Football: GK/DEF/MID/FWD. Basketball: G (guard), F (forward), C (centre). Tennis: S (singles player).
 * How a role feeds team strength is defined per sport in [SportProfile.roles]; the simulator never branches on sport.
 */
enum class Position { GK, DEF, MID, FWD, G, F, C, S }

@Serializable
data class Attributes(val attack: Int, val defense: Int, val physical: Int, val technique: Int, val mental: Int) {
    init { require(listOf(attack, defense, physical, technique, mental).all { it in 1..99 }) { "attributes must be 1..99" } }
}

@Serializable
data class Athlete(
    val id: String,
    val name: String,
    val position: Position,
    val attributes: Attributes,
    val fatigue: Int = 0,
    val injured: Boolean = false,
    val morale: Int = 50,
) {
    /** Role-weighted rating, 1..99. Weights per position sum to 1 (attack, defense, physical, technique, mental). */
    val overall: Int
        get() {
            val a = attributes
            val w = when (position) {
                Position.GK -> doubleArrayOf(0.05, 0.45, 0.15, 0.15, 0.20)
                Position.DEF -> doubleArrayOf(0.10, 0.40, 0.20, 0.15, 0.15)
                Position.MID -> doubleArrayOf(0.20, 0.20, 0.20, 0.20, 0.20)
                Position.FWD -> doubleArrayOf(0.45, 0.05, 0.15, 0.20, 0.15)
                Position.G -> doubleArrayOf(0.25, 0.15, 0.15, 0.30, 0.15)
                Position.F -> doubleArrayOf(0.30, 0.20, 0.25, 0.15, 0.10)
                Position.C -> doubleArrayOf(0.15, 0.35, 0.35, 0.05, 0.10)
                Position.S -> doubleArrayOf(0.25, 0.15, 0.20, 0.25, 0.15)
            }
            val v = a.attack * w[0] + a.defense * w[1] + a.physical * w[2] + a.technique * w[3] + a.mental * w[4]
            return v.toInt().coerceIn(1, 99)
        }

    /** Effective rating after fatigue and morale. */
    val effective: Double
        get() = overall * (1.0 - fatigue / 100.0 * 0.35) * (0.9 + morale / 100.0 * 0.2)
}
