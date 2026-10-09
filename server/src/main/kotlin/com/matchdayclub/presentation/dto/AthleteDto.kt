package com.matchdayclub.presentation.dto

import com.matchdayclub.domain.model.Athlete
import com.matchdayclub.domain.model.Lineup
import com.matchdayclub.domain.model.Training
import com.matchdayclub.engine.model.Tactics
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable data class AthleteDto(
    val id: String, val name: String, val sport: String, val position: String, val overall: Int,
    val attack: Int, val defense: Int, val physical: Int, val technique: Int, val mental: Int,
    val age: Int, val rarity: String, val fatigue: Int, val injured: Boolean, val injuredUntil: String?, val morale: Int,
    val potential: Int = 60,
    val xp: Int = 0, val xpNext: Int = 100,
)
@Serializable data class TrainingDto(val id: String, val athleteId: String, val attribute: String, val delta: Int, val endsAt: String, val remainingSeconds: Long)
@Serializable data class SquadResponse(val athletes: List<AthleteDto>, val trainings: List<TrainingDto>, val trainingSlots: Int, val serverTime: String)
@Serializable data class StartTrainingRequest(val athleteId: String, val attribute: String)
@Serializable data class AcademyClaimRequest(val sport: String? = null)
@Serializable data class LineupDto(val sport: String, val starters: List<String>, val bench: List<String>, val tactics: Tactics, val isDefault: Boolean)
@Serializable data class LineupResponse(val lineup: LineupDto, val athletes: List<AthleteDto>)
@Serializable data class SetLineupRequest(val starters: List<String>, val bench: List<String> = emptyList(), val tactics: Tactics = Tactics())

fun Athlete.toDto(now: Instant) = AthleteDto(id.toString(), name, sport.name, position.name, overall, attack, defense, physical, technique, mental, age, rarity.name, currentFatigue(now), isInjured(now), injuredUntil?.toString(), morale, potential, xp, com.matchdayclub.domain.rules.Growth.XP_PER_POINT)
fun Training.toDto(now: Instant) = TrainingDto(id.toString(), athleteId.toString(), attribute.name, delta, endsAt.toString(), maxOf(0, endsAt.epochSecond - now.epochSecond))
fun Lineup.toDto() = LineupDto(sport.name, starters.map { it.toString() }, bench.map { it.toString() }, tactics, isDefault)

@Serializable data class ScoutOfferDto(val slot: Int, val kind: String, val athlete: AthleteDto)
@Serializable data class ScoutReportDto(val offers: List<ScoutOfferDto>, val signedToday: Boolean, val nextAt: String)
