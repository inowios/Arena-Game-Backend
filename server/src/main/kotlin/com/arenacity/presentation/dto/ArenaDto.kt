package com.arenacity.presentation.dto

import com.arenacity.domain.rules.Economy
import com.arenacity.domain.usecase.arena.ArenaChest
import com.arenacity.domain.usecase.arena.ArenaOutcome
import com.arenacity.domain.usecase.arena.ArenaStart
import kotlinx.serialization.Serializable

/** POST /club/arena/start: [stake] 1 / 2 / 4 (the coins multiplier), [story] = the chapter when this is its story match (the club's current one). */
@Serializable data class ArenaStartRequest(val stake: Int = 1, val story: Int? = null)
/** The match handed out: [matchId] is the token the result must carry; the energy left. */
@Serializable data class ArenaStartResponse(val matchId: String, val stake: Int, val story: Int?, val energy: EnergyDto)
/**
 * POST /club/arena/result: the [matchId] of the start (taken once), [won] (a [draw] is refused: the Arena v4 has none),
 * [conceded] = the player surrendered, [heroes] = the ids of the club's heroes who played (the triple; the server sets their
 * experience by the result), [knockouts] = rival heroes taken out (0..3), [altSkills] = alternate skills used.
 */
@Serializable data class ArenaResultRequest(val matchId: String? = null, val seed: Int = 0, val won: Boolean, val draw: Boolean = false, val conceded: Boolean = false,
                                            val heroes: List<String> = emptyList(), val knockouts: Int = 0, val altSkills: Int = 0)
/**
 * [coins] paid ([rewarded] false once the day's [dailyCap] paid matches are used up: 0 then; [rewardedToday] after this one),
 * [trophies] now and [trophiesDelta], the [arena] stood in, the [winStreak]; [heroes] = the heroes who played as they are now,
 * [heroXp] = the experience each was given (less once a hero hits its daily cap); [story] = the story's current chapter
 * after the match (null once the season is over).
 */
@Serializable data class ArenaResultResponse(val matchId: String, val coins: Long, val rewarded: Boolean, val rewardedToday: Int, val dailyCap: Int,
                                             val trophies: Int, val trophiesDelta: Int, val arena: String, val winStreak: Int, val club: ClubDto,
                                             val heroes: List<HeroDto> = emptyList(), val heroXp: Int = 0, val story: StoryMatchDto? = null)
fun ArenaStart.toDto() = ArenaStartResponse(match.id.toString(), match.stake, match.story, energy.toDto())
fun ArenaOutcome.toDto() = ArenaResultResponse(match.id.toString(), coins, rewarded, rewardedToday, Economy.ARENA_REWARDED_PER_DAY, club.trophies, trophiesDelta, arena.key, club.liveStreak,
    club.toDto(), heroes.map { it.toDto() }, heroXp, story?.toDto())

/** GET /live/chest: the weekly arena chest by the club's arena (its trophies); [club] after a claim. */
@Serializable data class ArenaChestDto(val arena: String, val coins: Long, val gold: Int, val available: Boolean, val nextAt: String, val club: ClubDto? = null)
fun ArenaChest.toDto(club: com.arenacity.domain.model.Club? = null) = ArenaChestDto(arena.key, coins, gold, available, nextAt.toString(), club?.toDto())
