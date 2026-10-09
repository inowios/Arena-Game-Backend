package com.matchdayclub.presentation.dto

import com.matchdayclub.domain.usecase.live.LiveOutcome
import com.matchdayclub.domain.usecase.live.LiveStart
import com.matchdayclub.domain.usecase.live.LiveTeam
import com.matchdayclub.engine.model.Tactics
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable data class LiveStartRequest(val opponentClubId: String? = null)
@Serializable data class LiveTeamDto(val club: ClubRefDto, val starters: List<AthleteDto>, val bench: List<AthleteDto>, val tactics: Tactics, val overall: Int, val trophies: Int, val arena: String)
@Serializable data class LiveStartDto(val id: String, val seed: Long, val home: LiveTeamDto, val away: LiveTeamDto, val halfSeconds: Int, val kind: String = "ARENA")
@Serializable data class AthleteGrowthDto(val athleteId: String, val name: String, val xp: Int, val points: Int, val raised: List<String>, val overallBefore: Int, val overallAfter: Int, val skillPoints: Int = 0)
@Serializable data class LiveFinishDto(
    val outcome: String, val arena: String, val coins: Long, val xp: Int, val trophies: Int, val trophiesDelta: Int, val winStreak: Int, val pack: Boolean,
    val club: ClubDto, val athletes: List<AthleteDto>, val packAthlete: AthleteDto? = null, val growth: List<AthleteGrowthDto> = emptyList(),
)

fun LiveTeam.toDto(now: Instant) = LiveTeamDto(club.toRef(), starters.map { it.toDto(now) }, bench.map { it.toDto(now) }, tactics, overall, club.trophies, com.matchdayclub.domain.rules.Arenas.of(club.trophies).key)
fun LiveStart.toDto(now: Instant) = LiveStartDto(match.id.toString(), match.seed, home.toDto(now), away.toDto(now), halfSeconds)
fun LiveOutcome.toDto(now: Instant) = LiveFinishDto(outcome.name, arena.key, coins, reputation, club.trophies, trophiesDelta, club.liveStreak, pack != null, club.toDto(), athletes.map { it.toDto(now) }, pack?.toDto(now),
    growth.map { AthleteGrowthDto(it.athlete.id.toString(), it.athlete.name, it.xpGained, it.points, it.raised.map { k -> k.name }, it.overallBefore, it.overallAfter, it.skillPoints) })

@Serializable data class ArenaChestDto(val arena: String, val coins: Long, val gold: Int, val available: Boolean, val nextAt: String, val club: ClubDto? = null)
fun com.matchdayclub.domain.usecase.live.ArenaChest.toDto(club: com.matchdayclub.domain.model.Club? = null) = ArenaChestDto(arena.key, coins, gold, available, nextAt.toString(), club?.toDto())
