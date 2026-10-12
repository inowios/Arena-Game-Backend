package com.arenacity.domain.rules

import com.arenacity.domain.model.AttributeKind
import com.arenacity.domain.model.Athlete
import com.arenacity.engine.model.Position
import kotlin.random.Random

/** What a live match did for one athlete: experience earned, attribute points gained (and which). */
data class GrowthResult(val athlete: Athlete, val xpGained: Int, val points: Int, val raised: List<AttributeKind>, val overallBefore: Int, val overallAfter: Int, val skillPoints: Int = 0)

/**
 * Player development from matches (the FIFA-style side of the loop): starters earn experience for minutes, goals,
 * assists, saves and wins; the young earn faster, veterans slower; every [XP_PER_POINT] a role-weighted attribute
 * rises by one, until the athlete's potential (and the venue's cap) is reached.
 */
object Growth {
    const val XP_PER_POINT = 100
    const val BASE = 20
    const val GOAL = 15
    const val ASSIST = 10
    const val SAVE = 5
    const val WIN = 10
    const val DRAW = 4

    fun ageFactor(age: Int) = when { age <= 20 -> 1.6; age <= 23 -> 1.3; age <= 27 -> 1.0; age <= 30 -> 0.7; else -> 0.4 }

    fun xpFor(a: Athlete, minutes: Int, goals: Int, assists: Int, saves: Int, outcome: Outcome): Int {
        if (minutes <= 0) return 0
        val raw = BASE * minutes / 90.0 + goals * GOAL + assists * ASSIST + saves * SAVE + when (outcome) { Outcome.WIN -> WIN; Outcome.DRAW -> DRAW; Outcome.LOSS -> 0 }
        return (raw * ageFactor(a.age)).toInt().coerceAtLeast(1)
    }

    /** Role-weighted pick of the attribute to raise (attack for forwards, defence for defenders, …). */
    fun pick(position: Position, r: Random): AttributeKind {
        val w = when (position) {
            Position.GK -> intArrayOf(1, 5, 3, 3, 4)
            Position.DEF -> intArrayOf(1, 5, 3, 2, 2)
            Position.MID -> intArrayOf(3, 2, 2, 4, 3)
            Position.FWD -> intArrayOf(5, 1, 3, 3, 2)
            else -> intArrayOf(3, 3, 3, 3, 3)
        }
        var n = r.nextInt(w.sum())
        val kinds = AttributeKind.values()
        for (i in w.indices) { n -= w[i]; if (n < 0) return kinds[i] }
        return kinds.last()
    }

    /** Adds [xp] and turns full hundreds into attribute points while potential and [cap] allow. */
    fun apply(a: Athlete, xp: Int, cap: Int, r: Random): GrowthResult {
        // skill points for the technique trees come with experience whatever the attributes do
        val skill = Techniques.pointsFor(a.xpTotal + xp) - Techniques.pointsFor(a.xpTotal)
        var cur = a.copy(xp = a.xp + xp, xpTotal = a.xpTotal + xp, skillPoints = a.skillPoints + skill); val raised = mutableListOf<AttributeKind>()
        while (cur.xp >= XP_PER_POINT) {
            if (!Careers.canTrain(cur, 1)) { cur = cur.copy(xp = XP_PER_POINT - 1); break }   // at potential: experience waits for the next season's growth
            var kind = pick(a.position, r); var guard = 0
            while (cur.attribute(kind) >= cap.coerceAtMost(99) && guard++ < 8) kind = pick(a.position, r)
            if (cur.attribute(kind) >= cap.coerceAtMost(99)) { cur = cur.copy(xp = XP_PER_POINT - 1); break }
            cur = cur.withAttribute(kind, cur.attribute(kind) + 1).copy(xp = cur.xp - XP_PER_POINT); raised += kind
        }
        return GrowthResult(cur, xp, raised.size, raised, a.overall, cur.overall, skill)
    }
}
