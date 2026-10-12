package com.arenacity.domain.rules

import com.arenacity.domain.model.Athlete
import com.arenacity.domain.model.AttributeKind
import com.arenacity.domain.model.Sport
import kotlin.random.Random

/**
 * Athlete careers: potential (how far training can take the attribute average), a season of ageing (young players grow,
 * veterans decline, 34+ may retire), morale from playing time and results, squad size per section.
 */
object Careers {
    const val MORALE_MIN = 20
    const val MORALE_MAX = 95

    /** Squad size limit per section (academy and scouting stop there; rewards may go over). */
    fun squadMax(sport: Sport) = when (sport) { Sport.FOOTBALL -> 25; Sport.BASKETBALL -> 14; Sport.TENNIS -> 6 }

    /** Potential for a new athlete: the younger, the more room above the current quality. */
    fun potentialFor(age: Int, quality: Int, r: Random): Int {
        val room = when { age <= 19 -> r.nextInt(12, 27); age <= 21 -> r.nextInt(8, 20); age <= 24 -> r.nextInt(4, 12); age <= 28 -> r.nextInt(1, 6); else -> r.nextInt(0, 3) }
        return (quality + room).coerceIn(quality, 95)
    }

    /** Training is allowed while the attribute average (after the gain) stays within potential. */
    fun canTrain(a: Athlete, delta: Int) = a.attack + a.defense + a.physical + a.technique + a.mental + delta <= a.potential * 5

    fun retirementChance(age: Int) = when { age >= 36 -> 1.0; age == 35 -> 0.55; age == 34 -> 0.25; else -> 0.0 }

    /** Attribute points gained (+) or lost (−) over one season at the new [age]. */
    fun seasonDelta(age: Int) = when { age <= 20 -> 4; age <= 23 -> 3; age <= 26 -> 1; age <= 29 -> 0; age <= 31 -> -2; else -> -4 }

    /**
     * One season older: growth spread over random attributes (never past potential), decline hits physical first,
     * morale back toward 50. Retirement is decided separately ([retires]).
     */
    fun age(a: Athlete, r: Random): Athlete {
        var n = a.copy(age = a.age + 1, morale = (a.morale + 50) / 2)
        val delta = seasonDelta(n.age)
        if (delta > 0) repeat(delta) {
            if (!canTrain(n, 1)) return@repeat
            val k = AttributeKind.values()[r.nextInt(AttributeKind.values().size)]
            n = n.withAttribute(k, (n.attribute(k) + 1).coerceAtMost(99))
        } else if (delta < 0) repeat(-delta) { i ->
            val k = if (i % 2 == 0) AttributeKind.PHYSICAL else AttributeKind.values()[r.nextInt(AttributeKind.values().size)]
            n = n.withAttribute(k, (n.attribute(k) - 1).coerceAtLeast(1))
        }
        if (delta < 0) n = n.copy(potential = minOf(n.potential, n.attributeAverage + 1))
        return n
    }

    fun retires(a: Athlete, r: Random) = r.nextDouble() < retirementChance(a.age)

    /** Morale after one matchday section: starters by result, everyone else of that sport a little down. */
    fun moraleAfter(morale: Int, played: Boolean, points: Int): Int =
        (morale + if (!played) -2 else when (points) { 3 -> 5; 1 -> 1; else -> -4 }).coerceIn(MORALE_MIN, MORALE_MAX)
}
