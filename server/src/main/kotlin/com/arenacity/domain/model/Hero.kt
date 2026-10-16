package com.arenacity.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A hero's rarity: only its base strength and its price in gold («кристали»); rules.Heroes.price. */
enum class HeroGroup { COMMON, GOOD, STAR, LEGEND }

/**
 * One hero of the game as the catalog has it (rules.HeroCatalog, generated from the client's people.json): [people] null
 * = a human; [skills] = the ids of the three advanced skills, opened at hero levels 4 / 7 / 10 (rules.Heroes.SKILL_LEVELS).
 */
data class HeroSpec(val id: String, val name: String, val people: People?, val group: HeroGroup, val position: String, val role: String,
                    val power: String, val number: Int, val skills: List<String>)

/** How a club got a hero: the start six, a story chapter, bought for gold, or a mission (missions come later). */
enum class HeroSource { START, STORY, CRYSTALS, MISSION }

/**
 * A hero the club owns (one of each, for good; docs/claude/HEROES-INSTEAD-OF-CARDS.md in the mobile repo). [level] 1..10,
 * [xp] = experience into the current level (0 at the top level); [xpDay] / [xpToday] = the UTC day the hero
 * last earned match experience and how much that day (the daily cap, rules.Heroes.MAX_MATCH_XP_PER_DAY).
 */
data class ClubHero(val clubId: UUID, val heroId: String, val level: Int, val xp: Int, val source: HeroSource, val unlockedAt: Instant,
                    val xpDay: LocalDate? = null, val xpToday: Int = 0)
