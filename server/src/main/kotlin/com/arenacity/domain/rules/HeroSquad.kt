package com.arenacity.domain.rules

import com.arenacity.arena.HeroPick
import com.arenacity.domain.model.ClubHero
import com.arenacity.domain.model.HeroLineup

/**
 * The triple a club fields in a simulated fixture (the league, the cup, friendlies, challenges; APP-AUDIT §2.15, 20 October
 * 2026): its saved Arena triple (GET /club/heroes/lineup, the heroes with a kit in its order) filled up to three with its
 * best other owned heroes with a kit — the highest level, then the most experience, then the catalog order. A club without
 * heroes (a bot, an old club) fields a [seeded] triple of the eleven humans at level 1. The server plays the match itself
 * (usecase.league.HeroMatch), so the level is carried for the result only: v4 kits do not scale with it.
 */
object HeroSquad {
    /** The ids of the humans with a kit, in catalog order. */
    val HUMANS: List<String> = HeroCatalog.WITH_KIT.map { it.id }

    /** The club's triple from what it owns and saved; fewer than three only when the club owns fewer heroes with a kit. */
    fun triple(owned: List<ClubHero>, lineup: HeroLineup?): List<HeroPick> {
        val withKit = owned.filter { HeroCatalog.of(it.heroId)?.hasKit == true }
        val byId = withKit.associateBy { it.heroId }
        val picked = lineup?.heroes.orEmpty().distinct().mapNotNull { byId[it] }.take(Heroes.MAX_HEROES_PER_MATCH).toMutableList()
        val order = HUMANS.withIndex().associate { it.value to it.index }
        val rest = withKit.filter { it !in picked }.sortedWith(compareByDescending<ClubHero> { it.level }.thenByDescending { it.xp }.thenBy { order[it.heroId] ?: Int.MAX_VALUE })
        for (h in rest) { if (picked.size >= Heroes.MAX_HEROES_PER_MATCH) break; picked += h }
        return picked.map { HeroPick(it.heroId, it.level) }
    }

    /** Three distinct humans chosen by [seed] (a bot's or a hero-less club's triple), level 1. */
    fun seeded(seed: Long): List<HeroPick> {
        val rng = com.arenacity.arena.Rng(seed.toULong())
        val ids = HUMANS.toMutableList(); val t = ArrayList<HeroPick>(3)
        repeat(Heroes.MAX_HEROES_PER_MATCH) { t += HeroPick(ids.removeAt(rng.next(ids.size)), 1) }
        return t
    }

    /** [triple] when it is full, else filled with [seeded] heroes the club does not field already (a club with one or two heroes). */
    fun fill(triple: List<HeroPick>, seed: Long): List<HeroPick> {
        if (triple.size >= Heroes.MAX_HEROES_PER_MATCH) return triple
        val have = triple.map { it.id }.toSet()
        return triple + seeded(seed).filter { it.id !in have }.take(Heroes.MAX_HEROES_PER_MATCH - triple.size)
    }
}
