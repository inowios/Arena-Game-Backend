package com.arenacity.presentation.dto

import com.arenacity.domain.usecase.arena.ArenaChest
import com.arenacity.domain.usecase.arena.ArenaOutcome
import kotlinx.serialization.Serializable

/** POST /club/arena/start: the energy left. */
@Serializable data class ArenaStartResponse(val energy: EnergyDto)
/** POST /club/arena/result: [heroes] = the ids of the club's heroes who played ("p001"…); the server sets their experience by the result. */
@Serializable data class ArenaResultRequest(val seed: Int, val won: Boolean, val draw: Boolean = false, val goalsFor: Int = 0, val goalsAgainst: Int = 0, val stake: Int = 1, val conceded: Boolean = false,
                                            val heroes: List<String> = emptyList(), val story: Int? = null)
/** [heroes] = the heroes who played as they are now, [heroXp] = the experience each was given (less once a hero hits the daily cap); [story] = the story's current chapter after the match (null once the season is over). */
@Serializable data class ArenaResultResponse(val coins: Long, val club: ClubDto, val heroes: List<HeroDto> = emptyList(), val heroXp: Int = 0, val story: StoryMatchDto? = null)
fun ArenaOutcome.toDto() = ArenaResultResponse(coins, club.toDto(), heroes.map { it.toDto() }, heroXp, story?.toDto())

/** GET /live/chest: the weekly arena chest by the club's arena (its trophies); [club] after a claim. */
@Serializable data class ArenaChestDto(val arena: String, val coins: Long, val gold: Int, val available: Boolean, val nextAt: String, val club: ClubDto? = null)
fun ArenaChest.toDto(club: com.arenacity.domain.model.Club? = null) = ArenaChestDto(arena.key, coins, gold, available, nextAt.toString(), club?.toDto())
