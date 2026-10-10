package com.matchdayclub.domain.rules

import com.matchdayclub.engine.model.Position
import kotlin.random.Random

/**
 * Techniques — the eight moves of the live match (with the ball: shoot, pass, cross, feint; without: tackle, slide,
 * block, jump), each a five-step tree an athlete climbs with skill points. Points come with match experience
 * (one per [XP_PER_SKILL_POINT]); the client applies the levels in its simulation, the server only keeps the books:
 * which level, what it costs, who may learn what. Level 5 is the signature move.
 */
object Techniques {
    const val MAX_LEVEL = 5
    const val XP_PER_SKILL_POINT = 120
    val KEYS = listOf("SHOOT", "PASS", "CROSS", "FEINT", "TACKLE", "SLIDE", "BLOCK", "JUMP")
    private val COST = intArrayOf(1, 1, 2, 3, 4)

    /** Skill points the step to [level] (1..5) costs; a point less (never below one) for the athlete's talent. */
    fun cost(level: Int, talent: Boolean = false): Int = (COST[(level - 1).coerceIn(0, MAX_LEVEL - 1)] - (if (talent) 1 else 0)).coerceAtLeast(1)

    /** Outfield footballers learn the moves; keepers (and other sports) do not. */
    fun learns(position: Position): Boolean = position in setOf(Position.DEF, Position.MID, Position.FWD)
    fun allowed(position: Position, key: String): Boolean = key in KEYS && learns(position)

    /** Skill points earned by [xpTotal] experience over a career. */
    fun pointsFor(xpTotal: Int): Int = xpTotal / XP_PER_SKILL_POINT

    private val favourites = mapOf(
        Position.FWD to listOf("SHOOT", "FEINT", "SHOOT", "CROSS", "PASS", "JUMP"),
        Position.MID to listOf("PASS", "CROSS", "FEINT", "TACKLE", "SHOOT", "PASS"),
        Position.DEF to listOf("TACKLE", "SLIDE", "BLOCK", "JUMP", "TACKLE", "PASS"),
    )

    /** The move an athlete is born for: one of the role's favourites, the first for the gifted, random for the rest. */
    fun talent(position: Position, quality: Int, r: Random): String? {
        val fav = favourites[position] ?: return null
        return if (quality >= 60 && r.nextInt(100) < 50) fav[0] else fav[r.nextInt(fav.size)]
    }

    /** What a generated athlete already knows: the better he is, the more (and higher) the moves of his role. */
    fun starting(position: Position, quality: Int, r: Random): Map<String, Int> {
        val fav = favourites[position] ?: return emptyMap()
        val count = when { quality >= 72 -> 3; quality >= 62 -> 2; quality >= 50 -> if (r.nextInt(100) < 70) 1 else 2; else -> if (r.nextInt(100) < 45) 1 else 0 }
        val maxLevel = when { quality >= 72 -> 3; quality >= 58 -> 2; else -> 1 }
        val out = mutableMapOf<String, Int>()
        var guard = 0
        while (out.size < count && guard++ < 20) { val k = fav[r.nextInt(fav.size)]; if (k !in out) out[k] = 1 + r.nextInt(maxLevel) }
        return out
    }
}
