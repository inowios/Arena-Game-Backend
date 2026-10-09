package com.matchdayclub.presentation.dto

import com.matchdayclub.domain.model.Building
import com.matchdayclub.domain.model.BuildingState
import com.matchdayclub.domain.model.Club
import com.matchdayclub.domain.rules.BuildingCatalog
import com.matchdayclub.domain.rules.BuildingSpec
import com.matchdayclub.domain.rules.ClubLevels
import com.matchdayclub.domain.rules.LevelSpec
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable data class CreateClubRequest(val name: String, val primaryColor: String, val secondaryColor: String, val timezone: String = "UTC")
@Serializable data class UpdateClubRequest(val name: String? = null, val primaryColor: String? = null, val secondaryColor: String? = null, val emblemJson: String? = null)
@Serializable data class PlaceBuildingRequest(val type: String, val x: Int, val y: Int, val rotation: Int = 0, val variant: String? = null)
@Serializable data class MoveBuildingRequest(val x: Int, val y: Int, val rotation: Int = 0)

@Serializable data class ClubDto(
    val id: String, val name: String, val primaryColor: String, val secondaryColor: String, val emblemJson: String?,
    val level: Int, val reputation: Int, val reputationForNextLevel: Int?, val coins: Long, val gold: Int, val timezone: String,
    val academyNextAt: String?, val medicalNextAt: String?,
    val streak: Int = 0, val loginDay: Int = 0,
    val builderSlots: Int = 1,
    val division: Int = 1, val seasonNo: Int = 1,
    val kit: String? = null, val emblemFrame: String? = null, val celebration: String? = null,
    val trophies: Int = 0, val arena: String = "YARD", val liveStreak: Int = 0,
)

/** Rewarded-ad state for the club screen: double income window/cooldown and today's free speed-up. */
@Serializable data class AdsStateDto(val doubleIncomeUntil: String?, val doubleIncomeReadyAt: String?, val freeSpeedupAvailable: Boolean)

@Serializable data class LevelSpecDto(val level: Int, val cost: Long, val buildSeconds: Long, val requiredClubLevel: Int, val incomePerHour: Long, val capHours: Int, val effect: Int, val effect2: Int)
@Serializable data class BuildingSpecDto(val type: String, val width: Int, val height: Int, val maxPerClub: Int, val enabled: Boolean, val levels: List<LevelSpecDto>)

@Serializable data class BuildingDto(
    val id: String, val type: String, val level: Int, val x: Int, val y: Int, val rotation: Int, val state: String,
    val upgradeEndsAt: String?, val remainingSeconds: Long, val speedUpGold: Int, val pendingIncome: Long, val incomePerHour: Long, val capHours: Int,
    val nextLevel: LevelSpecDto?,
    val variant: String? = null,
)

@Serializable data class ClubStateResponse(val club: ClubDto, val buildings: List<BuildingDto>, val gridSize: Int, val serverTime: String, val sections: List<String> = listOf("FOOTBALL"), val ads: AdsStateDto? = null, val inboxUnread: Int = 0)
@Serializable data class CollectResponse(val building: BuildingDto, val coins: Long, val club: ClubDto)

fun Club.toDto() = ClubDto(id.toString(), name, primaryColor, secondaryColor, emblemJson, level, reputation, ClubLevels.reputationForNext(level), coins, gold, timezone, academyNextAt?.toString(), medicalNextAt?.toString(), streak, loginDay, builderSlots, division, seasonNo, kit, emblemFrame, celebration, trophies, com.matchdayclub.domain.rules.Arenas.of(trophies).key, liveStreak)

fun Club.adsState(now: Instant): AdsStateDto {
    val cooldown = com.matchdayclub.domain.rules.Economy.DOUBLE_INCOME_COOLDOWN_SECONDS
    val today = now.atZone(java.time.ZoneId.of(timezone)).toLocalDate()
    return AdsStateDto(doubleIncomeUntil?.takeIf { it.isAfter(now) }?.toString(), doubleIncomeAdAt?.plusSeconds(cooldown)?.takeIf { it.isAfter(now) }?.toString(), freeSpeedupDate != today)
}

fun LevelSpec.toDto(level: Int) = LevelSpecDto(level, cost, buildSeconds, requiredClubLevel, incomePerHour, capHours, effect, effect2)
fun BuildingSpec.toDto() = BuildingSpecDto(type.name, width, height, if (maxPerClub == Int.MAX_VALUE) -1 else maxPerClub, enabled, levels.mapIndexed { i, l -> l.toDto(i + 1) })

fun Building.toDto(now: Instant, clubLevel: Int): BuildingDto {
    val spec = BuildingCatalog.level(type, level)
    val remaining = if (state == BuildingState.UPGRADING && upgradeEndsAt != null) maxOf(0, upgradeEndsAt.epochSecond - now.epochSecond) else 0
    val pending = if (state == BuildingState.IDLE) BuildingCatalog.income(spec, now.epochSecond - lastCollectedAt.epochSecond, clubLevel) else 0
    val next = if (BuildingCatalog.hasLevel(type, level + 1)) BuildingCatalog.level(type, level + 1).toDto(level + 1) else null
    return BuildingDto(id.toString(), type.name, level, gridX, gridY, rotation, state.name, upgradeEndsAt?.toString(), remaining, BuildingCatalog.speedUpGold(remaining), pending, spec.incomePerHour, spec.capHours, next, variant)
}
