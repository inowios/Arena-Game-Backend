package com.arenacity.domain.rules

import com.arenacity.domain.model.People

/**
 * The five peoples of the archipelago and the cards they bring (the owner, 15 October 2026; the roster:
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

    /**
     * Whether a card of [people] (null = a human) may drop from a pack bought with coins or gold: humans always, another
     * people only once its island is open (its bridge stands); before that its cards are bought for rubies only.
     *
     * TODO(packs): the pack engine (rules.Packs, usecase.athlete.PackUseCases) has no peoples yet — its cards are
     * generated athletes with no people. When the 60-character roster lands on the server, filter each pull's candidates
     * with this (and sell a closed people's cards only in the ruby shop).
     */
    fun dropsInPacks(people: People?, open: Set<People>): Boolean = people == null || people in open
}
