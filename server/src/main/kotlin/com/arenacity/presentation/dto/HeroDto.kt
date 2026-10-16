package com.arenacity.presentation.dto

import com.arenacity.domain.rules.Heroes
import com.arenacity.domain.usecase.hero.HeroRoster
import com.arenacity.domain.usecase.hero.HeroUnlockResult
import com.arenacity.domain.usecase.hero.HeroView
import kotlinx.serialization.Serializable

/** How a hero opens: kind START | STORY | FOLK, [chapter] for STORY, [stadium] = the stadium level it needs, [price] in gold. */
@Serializable data class HeroUnlockRuleDto(val kind: String, val chapter: Int?, val stadium: Int, val price: Int)

/**
 * A hero for the «Герої» screen. folk = HUMAN | BEAST | STONE | ANDROID | SHADOW, group = COMMON | GOOD | STAR | LEGEND;
 * [skills] = the three advanced skill ids, [unlockedSkills] = those the level has opened (levels 4 / 7 / 10);
 * a locked hero shows level 1 and no source.
 */
@Serializable data class HeroDto(
    val id: String, val name: String, val folk: String, val group: String, val position: String, val role: String, val power: String, val number: Int,
    val owned: Boolean, val level: Int, val xp: Int, val xpToNext: Int?, val maxLevel: Int, val boostPct: Int,
    val skills: List<String>, val unlockedSkills: List<String>, val nextSkillLevel: Int?,
    val source: String?, val unlockedAt: String?, val unlock: HeroUnlockRuleDto, val available: Boolean, val canBuy: Boolean,
)
/** GET /club/heroes: every hero; [levels] = experience from each level to the next (1→2 … 9→10), [skillLevels] = 4, 7, 10. */
@Serializable data class HeroRosterResponse(val heroes: List<HeroDto>, val owned: Int, val gold: Int, val stadium: Int, val levels: List<Int>, val skillLevels: List<Int>,
                                            val maxMatchXpPerDay: Int)
@Serializable data class HeroUnlockResponse(val hero: HeroDto, val club: ClubDto, val spent: Int)

fun HeroView.toDto(): HeroDto {
    val level = owned?.level ?: 1
    return HeroDto(
        spec.id, spec.name, spec.people?.name ?: "HUMAN", spec.group.name, spec.position, spec.role, spec.power, spec.number,
        owned != null, level, owned?.xp ?: 0, Heroes.xpToNext(level)?.let { it - (owned?.xp ?: 0) }, Heroes.MAX_LEVEL, Heroes.boostPct(level),
        spec.skills, if (owned != null) Heroes.unlockedSkills(spec, level) else emptyList(), Heroes.nextSkillLevel(level),
        owned?.source?.name, owned?.unlockedAt?.toString(), HeroUnlockRuleDto(rule.kind.name, rule.chapter, rule.stadium, rule.gold), available, canBuy,
    )
}
fun HeroRoster.toDto() = HeroRosterResponse(heroes.map { it.toDto() }, heroes.count { it.owned != null }, club.gold, stadium, Heroes.XP_TO_NEXT, Heroes.SKILL_LEVELS,
    Heroes.MAX_MATCH_XP_PER_DAY)
fun HeroUnlockResult.toDto() = HeroUnlockResponse(hero.toDto(), club.toDto(), spent)
