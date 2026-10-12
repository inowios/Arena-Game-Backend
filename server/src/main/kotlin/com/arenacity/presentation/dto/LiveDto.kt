package com.arenacity.presentation.dto

import com.arenacity.domain.usecase.live.LiveOutcome
import com.arenacity.domain.usecase.live.LiveStart
import com.arenacity.domain.usecase.live.LiveTeam
import com.arenacity.engine.model.Tactics
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable data class LiveStartRequest(val opponentClubId: String? = null)
@Serializable data class LiveTeamDto(val club: ClubRefDto, val starters: List<AthleteDto>, val bench: List<AthleteDto>, val tactics: Tactics, val overall: Int, val trophies: Int, val arena: String)
@Serializable data class LiveStartDto(val id: String, val seed: Long, val home: LiveTeamDto, val away: LiveTeamDto, val halfSeconds: Int, val kind: String = "ARENA", val seats: Int = 500)
@Serializable data class AthleteGrowthDto(val athleteId: String, val name: String, val xp: Int, val points: Int, val raised: List<String>, val overallBefore: Int, val overallAfter: Int, val skillPoints: Int = 0)
@Serializable data class LiveFinishDto(
    val outcome: String, val arena: String, val coins: Long, val xp: Int, val trophies: Int, val trophiesDelta: Int, val winStreak: Int, val pack: Boolean,
    val club: ClubDto, val athletes: List<AthleteDto>, val packAthlete: AthleteDto? = null, val growth: List<AthleteGrowthDto> = emptyList(),
    /** Part of [coins] that was the gate of the home stadium. */
    val gate: Long = 0,
)

fun LiveTeam.toDto(now: Instant) = LiveTeamDto(club.toRef(), starters.map { it.toDto(now) }, bench.map { it.toDto(now) }, tactics, overall, club.trophies, com.arenacity.domain.rules.Arenas.of(club.trophies).key)
fun LiveStart.toDto(now: Instant) = LiveStartDto(match.id.toString(), match.seed, home.toDto(now), away.toDto(now), halfSeconds, seats = seats)
fun LiveOutcome.toDto(now: Instant) = LiveFinishDto(outcome.name, arena.key, coins, reputation, club.trophies, trophiesDelta, club.liveStreak, pack != null, club.toDto(), athletes.map { it.toDto(now) }, pack?.toDto(now),
    growth.map { AthleteGrowthDto(it.athlete.id.toString(), it.athlete.name, it.xpGained, it.points, it.raised.map { k -> k.name }, it.overallBefore, it.overallAfter, it.skillPoints) }, gate)

@Serializable data class LiveRecentDto(val id: String, val opponent: ClubRefDto?, val homeScore: Int, val awayScore: Int, val trophiesDelta: Int, val status: String, val finishedAt: String?)
fun com.arenacity.domain.usecase.live.RecentLiveMatchesUseCase.Row.toDto() = LiveRecentDto(match.id.toString(), opponent?.toRef(), match.homeScore ?: 0, match.awayScore ?: 0, match.trophiesDelta ?: 0, match.status.name, match.finishedAt?.toString())

@Serializable data class AthleteMatchDto(val id: String, val opponent: ClubRefDto?, val homeScore: Int, val awayScore: Int, val minutes: Int, val goals: Int, val assists: Int, val finishedAt: String?)
@Serializable data class AthleteHistoryDto(val athleteId: String, val matches: Int, val wins: Int, val goals: Int, val assists: Int, val recent: List<AthleteMatchDto>)
fun com.arenacity.domain.usecase.live.AthleteMatchHistoryUseCase.History.toDto() = AthleteHistoryDto(athlete.id.toString(), matches, wins, goals, assists,
    rows.map { AthleteMatchDto(it.match.id.toString(), it.opponent?.toRef(), it.match.homeScore ?: 0, it.match.awayScore ?: 0, it.minutes, it.goals, it.assists, it.match.finishedAt?.toString()) })

@Serializable data class ArenaChestDto(val arena: String, val coins: Long, val gold: Int, val available: Boolean, val nextAt: String, val club: ClubDto? = null)
fun com.arenacity.domain.usecase.live.ArenaChest.toDto(club: com.arenacity.domain.model.Club? = null) = ArenaChestDto(arena.key, coins, gold, available, nextAt.toString(), club?.toDto())
