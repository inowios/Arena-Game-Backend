package com.arenacity.presentation.dto

import com.arenacity.domain.rules.Heroes
import com.arenacity.domain.usecase.hero.HeroClaimResult
import com.arenacity.domain.usecase.hero.HeroLineupView
import com.arenacity.domain.usecase.hero.HeroRoster
import com.arenacity.domain.usecase.hero.HeroTrainResult
import com.arenacity.domain.usecase.hero.HeroTrainingStatus
import com.arenacity.domain.usecase.hero.HeroUnlockResult
import com.arenacity.domain.usecase.hero.HeroView
import kotlinx.serialization.Serializable

/** How a hero opens: kind START | STORY | FOLK, [chapter] for STORY, [stadium] = the stadium level it needs, [price] in gold. */
@Serializable data class HeroUnlockRuleDto(val kind: String, val chapter: Int?, val stadium: Int, val price: Int)

/** A hero's session in the training centre: [xp] it gives, [endsAt], [secondsLeft] (0 = [done], ready to claim). */
@Serializable data class HeroTrainingDto(val xp: Int, val endsAt: String, val secondsLeft: Long, val done: Boolean)

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
    /** null = not training. */
    val training: HeroTrainingDto? = null,
)
/** GET /club/heroes: every hero; [levels] = experience from each level to the next (1→2 … 9→10), [skillLevels] = 4, 7, 10. */
@Serializable data class HeroRosterResponse(val heroes: List<HeroDto>, val owned: Int, val gold: Int, val stadium: Int, val levels: List<Int>, val skillLevels: List<Int>,
                                            val maxMatchXpPerDay: Int)
@Serializable data class HeroUnlockResponse(val hero: HeroDto, val club: ClubDto, val spent: Int)

/** GET / PUT /club/heroes/lineup: up to [maxField] field heroes, the keeper (null = an academy pupil), up to [maxBench] on the bench. */
@Serializable data class HeroLineupDto(val field: List<String>, val keeper: String?, val bench: List<String>, val saved: Boolean, val updatedAt: String?,
                                       val maxField: Int = Heroes.LINEUP_FIELD, val maxBench: Int = Heroes.LINEUP_BENCH)
@Serializable data class HeroLineupRequest(val field: List<String> = emptyList(), val keeper: String? = null, val bench: List<String> = emptyList())

/**
 * GET /club/heroes/training: [available] false = no working training centre ([centerLevel] null, nothing can train);
 * a session costs [coins], gives [xp] and lasts [seconds]; [slots] heroes at once, [busy] of them in [heroes] now.
 */
@Serializable data class HeroTrainingStatusDto(val available: Boolean, val centerLevel: Int?, val slots: Int, val busy: Int, val coins: Long, val xp: Int,
                                               val seconds: Long, val heroes: List<HeroDto>)
@Serializable data class HeroTrainResponse(val hero: HeroDto, val club: ClubDto, val spent: Long, val training: HeroTrainingStatusDto)
@Serializable data class HeroClaimResponse(val hero: HeroDto, val gained: Int, val levelBefore: Int, val training: HeroTrainingStatusDto)

fun HeroView.toDto(): HeroDto {
    val level = owned?.level ?: 1
    return HeroDto(
        spec.id, spec.name, spec.people?.name ?: "HUMAN", spec.group.name, spec.position, spec.role, spec.power, spec.number,
        owned != null, level, owned?.xp ?: 0, Heroes.xpToNext(level)?.let { it - (owned?.xp ?: 0) }, Heroes.MAX_LEVEL, Heroes.boostPct(level),
        spec.skills, if (owned != null) Heroes.unlockedSkills(spec, level) else emptyList(), Heroes.nextSkillLevel(level),
        owned?.source?.name, owned?.unlockedAt?.toString(), HeroUnlockRuleDto(rule.kind.name, rule.chapter, rule.stadium, rule.gold), available, canBuy,
        owned?.trainingEndsAt?.let { end -> val left = trainingLeft ?: 0; HeroTrainingDto(owned.trainingXp, end.toString(), left, left == 0L) },
    )
}
fun HeroRoster.toDto() = HeroRosterResponse(heroes.map { it.toDto() }, heroes.count { it.owned != null }, club.gold, stadium, Heroes.XP_TO_NEXT, Heroes.SKILL_LEVELS,
    Heroes.MAX_MATCH_XP_PER_DAY)
fun HeroUnlockResult.toDto() = HeroUnlockResponse(hero.toDto(), club.toDto(), spent)
fun HeroLineupView.toDto() = HeroLineupDto(field, keeper, bench, saved, updatedAt?.toString())
fun HeroTrainingStatus.toDto() = HeroTrainingStatusDto(centerLevel != null, centerLevel, slots, training.size, coins, xp, seconds, training.map { it.toDto() })
fun HeroTrainResult.toDto() = HeroTrainResponse(hero.toDto(), club.toDto(), spent, status.toDto())
fun HeroClaimResult.toDto() = HeroClaimResponse(hero.toDto(), gained, levelBefore, status.toDto())
