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
 * the Arena v4 kit (arena_skills.json): [colour] = the energy colour (speed | technique | tactics | strength; null without
 * a kit), [skills] = the four skill ids (s0..s3), [alternates] (aN), [passives] (pN); [hasKit] false = no kit yet (the
 * peoples' heroes): not in the Arena, not for sale — [available] false with [reason] SOON. A locked hero shows level 1
 * and no source. The kit is the same at every level.
 */
@Serializable data class HeroDto(
    val id: String, val name: String, val folk: String, val group: String, val position: String, val role: String, val power: String, val number: Int,
    val colour: String?, val hasKit: Boolean, val skills: List<String>, val alternates: List<String>, val passives: List<String>,
    val owned: Boolean, val level: Int, val xp: Int, val xpToNext: Int?, val maxLevel: Int,
    val source: String?, val unlockedAt: String?, val unlock: HeroUnlockRuleDto, val available: Boolean, val canBuy: Boolean,
    /** Why the hero is not available: SOON (no Arena kit yet); null when it is, or the club owns it. */
    val reason: String? = null,
    /** null = not training. */
    val training: HeroTrainingDto? = null,
)
/** GET /club/heroes: every hero; [levels] = experience from each level to the next (1→2 … 9→10), [maxPerMatch] = the Arena triple. */
@Serializable data class HeroRosterResponse(val heroes: List<HeroDto>, val owned: Int, val gold: Int, val stadium: Int, val levels: List<Int>,
                                            val maxMatchXpPerDay: Int, val maxPerMatch: Int = Heroes.MAX_HEROES_PER_MATCH)
@Serializable data class HeroUnlockResponse(val hero: HeroDto, val club: ClubDto, val spent: Int)

/** GET / PUT /club/heroes/lineup: the Arena triple — up to [max] (3) owned heroes with a kit, in order; [saved] false = none saved. */
@Serializable data class HeroLineupDto(val heroes: List<String>, val saved: Boolean, val updatedAt: String?, val max: Int = Heroes.MAX_HEROES_PER_MATCH)
@Serializable data class HeroLineupRequest(val heroes: List<String> = emptyList())

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
        spec.colour, spec.hasKit, spec.skills, spec.alternates, spec.passives,
        owned != null, level, owned?.xp ?: 0, Heroes.xpToNext(level)?.let { it - (owned?.xp ?: 0) }, Heroes.MAX_LEVEL,
        owned?.source?.name, owned?.unlockedAt?.toString(), HeroUnlockRuleDto(rule.kind.name, rule.chapter, rule.stadium, rule.gold), available, canBuy, reason,
        owned?.trainingEndsAt?.let { end -> val left = trainingLeft ?: 0; HeroTrainingDto(owned.trainingXp, end.toString(), left, left == 0L) },
    )
}
fun HeroRoster.toDto() = HeroRosterResponse(heroes.map { it.toDto() }, heroes.count { it.owned != null }, club.gold, stadium, Heroes.XP_TO_NEXT, Heroes.MAX_MATCH_XP_PER_DAY)
fun HeroUnlockResult.toDto() = HeroUnlockResponse(hero.toDto(), club.toDto(), spent)
fun HeroLineupView.toDto() = HeroLineupDto(heroes, saved, updatedAt?.toString())
fun HeroTrainingStatus.toDto() = HeroTrainingStatusDto(centerLevel != null, centerLevel, slots, training.size, coins, xp, seconds, training.map { it.toDto() })
fun HeroTrainResult.toDto() = HeroTrainResponse(hero.toDto(), club.toDto(), spent, status.toDto())
fun HeroClaimResult.toDto() = HeroClaimResponse(hero.toDto(), gained, levelBefore, status.toDto())
