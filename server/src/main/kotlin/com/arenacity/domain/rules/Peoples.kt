package com.arenacity.domain.rules

import com.arenacity.domain.model.People

/**
 * The five peoples of the archipelago and the heroes they bring (the owner, 15 October 2026; the roster:
 * docs/claude/CARD-GAME-ROSTER-PROGRESSION.md §5 in the mobile repo). Humans live on the main island and are always
 * there; each other people lives on its side island, sealed under a dome until the club's bridge to it stands
 * (rules.Zones) — then the people has «returned».
 */
object Peoples {
    /** The era (1..4) in which a people returns: the stadium levels 1–3 / 4–6 / 7–9 / 10; humans (null) era 1. */
    fun era(people: People?): Int = when (people) {
        null -> 1
        People.BEAST, People.STONE -> 2
        People.ANDROID -> 3
        People.SHADOW -> 4
    }

    /** The peoples that have returned to a club: those whose island is open ([open]: the club's OPEN zones). */
    fun returned(open: Set<People>): List<People> = People.entries.filter { it in open }
}
