package com.arenacity.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A hero's rarity: only its base strength and its price in gold («кристали»); rules.Heroes.price. */
enum class HeroGroup { COMMON, GOOD, STAR, LEGEND }

/**
 * One hero of the game as the catalog has it (rules.HeroCatalog, generated from the client's people.json and
 * arena_skills.json): [people] null = a human; the Arena v4 kit — [colour] = the hero's energy colour (speed, technique,
 * tactics, strength; decoration), [skills] = its four skill ids (s0..s3), [alternates] = the skills that replace one of
 * them while a state holds (aN), [passives] = always on (pN). A hero without a kit ([hasKit] false: the peoples' heroes,
 * p012+) cannot play the Arena yet. The kit is the same at every level: the level is strength, not new skills.
 */
data class HeroSpec(val id: String, val name: String, val people: People?, val group: HeroGroup, val position: String, val role: String,
                    val power: String, val number: Int, val colour: String?, val skills: List<String>, val alternates: List<String> = emptyList(),
                    val passives: List<String> = emptyList()) {
    val hasKit: Boolean get() = skills.isNotEmpty()
}

/** How a club got a hero: the start six, a story chapter, bought for gold, or a mission (missions come later). */
enum class HeroSource { START, STORY, CRYSTALS, MISSION }

/**
 * A hero the club owns (one of each, for good; docs/claude/HEROES-INSTEAD-OF-CARDS.md in the mobile repo). [level] 1..10,
 * [xp] = experience into the current level (0 at the top level); [xpDay] / [xpToday] = the UTC day the hero
 * last earned match experience and how much that day (the daily cap, rules.Heroes.MAX_MATCH_XP_PER_DAY);
 * [trainingEndsAt] / [trainingXp] = a session in the training centre (null = not training) and what it will give.
 */
data class ClubHero(val clubId: UUID, val heroId: String, val level: Int, val xp: Int, val source: HeroSource, val unlockedAt: Instant,
                    val xpDay: LocalDate? = null, val xpToday: Int = 0, val trainingEndsAt: Instant? = null, val trainingXp: Int = 0) {
    val training: Boolean get() = trainingEndsAt != null
}

/**
 * The club's Arena triple («Склад»): up to three heroes with a kit, in order (rules.Heroes.lineupError); fewer than three
 * = the match fills the rest with the club's best heroes itself.
 */
data class HeroLineup(val clubId: UUID, val heroes: List<String>, val updatedAt: Instant)
