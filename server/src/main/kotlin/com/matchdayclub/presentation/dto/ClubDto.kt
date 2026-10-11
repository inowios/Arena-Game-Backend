package com.matchdayclub.presentation.dto

import com.matchdayclub.domain.model.Building
import com.matchdayclub.domain.model.BuildingState
import com.matchdayclub.domain.model.Club
import com.matchdayclub.domain.rules.BuildingCatalog
import com.matchdayclub.domain.rules.BuildingSpec
import com.matchdayclub.domain.rules.ClubLevels
import com.matchdayclub.domain.rules.LevelSpec
import com.matchdayclub.domain.rules.ReservedPlots
import com.matchdayclub.domain.rules.Stadium
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable data class CreateClubRequest(val name: String, val primaryColor: String, val secondaryColor: String, val timezone: String = "UTC")
@Serializable data class UpdateClubRequest(val name: String? = null, val primaryColor: String? = null, val secondaryColor: String? = null, val emblemJson: String? = null)
@Serializable data class PlaceBuildingRequest(val type: String, val x: Int, val y: Int, val rotation: Int = 0, val variant: String? = null)
@Serializable data class MoveBuildingRequest(val x: Int, val y: Int, val rotation: Int = 0)
@Serializable data class CellDto(val x: Int, val y: Int)
/** A line of fence cells drawn by a drag. */
@Serializable data class PlaceFencesRequest(val cells: List<CellDto>, val type: String = "FENCE")
@Serializable data class IdsRequest(val ids: List<String>)
@Serializable data class PlacementDto(val id: String, val x: Int, val y: Int, val rotation: Int = 0)
/** Edit mode: every moved building at once. */
@Serializable data class LayoutRequest(val moves: List<PlacementDto>)
@Serializable data class SaveLayoutRequest(val name: String = "")
@Serializable data class LayoutSlotDto(val slot: Int, val name: String, val placements: List<PlacementDto>, val updatedAt: String)

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

/** [requiredStadium]: stadium level this level needs; [requiredTrophies]: for the stadium — the trophies a bigger stadium needs. */
@Serializable data class LevelSpecDto(val level: Int, val cost: Long, val buildSeconds: Long, val requiredStadium: Int, val requiredTrophies: Int, val incomePerHour: Long, val capHours: Int, val effect: Int, val effect2: Int)
/** [counts]/[caps]: copies allowed and highest level allowed by stadium level (index 0 = stadium 1); [unlockStadium]: first stadium level with a copy. */
@Serializable data class BuildingSpecDto(val type: String, val width: Int, val height: Int, val maxPerClub: Int, val enabled: Boolean, val levels: List<LevelSpecDto>,
                                         val counts: List<Int> = emptyList(), val caps: List<Int> = emptyList(), val unlockStadium: Int = 1,
                                         /** The sport's own plot in the reserved land (courts only; plotW = 0 when none) and where its court stands. */
                                         val plotX: Int = 0, val plotY: Int = 0, val plotW: Int = 0, val plotH: Int = 0, val courtX: Int = 0, val courtY: Int = 0)

@Serializable data class BuildingDto(
    val id: String, val type: String, val level: Int, val x: Int, val y: Int, val rotation: Int, val state: String,
    val upgradeEndsAt: String?, val remainingSeconds: Long, val speedUpGold: Int, val pendingIncome: Long, val incomePerHour: Long, val capHours: Int,
    val nextLevel: LevelSpecDto?,
    val variant: String? = null,
    /** Atmosphere bonus to income in % (total and its parts: decor around, next to the stadium, a car park nearby). */
    val bonusPct: Int = 0, val bonusDecor: Int = 0, val bonusStadium: Int = 0, val bonusParking: Int = 0, val bonusFence: Int = 0,
    val bonusMuseum: Int = 0, val bonusCollection: Int = 0, val bonusEvent: Int = 0,
)

@Serializable data class ClubStateResponse(val club: ClubDto, val buildings: List<BuildingDto>, val gridSize: Int, val serverTime: String, val sections: List<String> = listOf("FOOTBALL"), val ads: AdsStateDto? = null, val inboxUnread: Int = 0, val live: LiveReadyDto? = null,
                                          /** The stadium level whose limits apply now (the club's town hall). */ val stadium: Int = 1,
                                          val obstacles: List<ObstacleDto> = emptyList(),
                                          /** The land: owned parcels (col, row) of the 6×6 field of 12-cell parcels, and the next parcel's price. */
                                          val parcels: List<ParcelDto> = emptyList(), val land: LandDto? = null)
@Serializable data class ParcelDto(val col: Int, val row: Int)
@Serializable data class LandDto(val parcel: Int, val parcels: Int, val nextPrice: Long)
fun com.matchdayclub.domain.usecase.club.LandView.toDto() = LandDto(com.matchdayclub.domain.rules.Land.PARCEL, com.matchdayclub.domain.rules.Land.PARCELS, nextPrice)
@Serializable data class LandResponse(val parcels: List<ParcelDto>, val land: LandDto, val club: ClubDto)
/** An obstacle on the plot: [cost]/[seconds] to clear it; [rewardGold] only once it is cleared (READY). */
@Serializable data class ObstacleDto(val id: String, val kind: String, val x: Int, val y: Int, val w: Int, val h: Int, val state: String, val remainingSeconds: Long,
                                     val cost: Long, val seconds: Long, val speedUpGold: Int, val rewardGold: Int)
@Serializable data class ObstacleLootResponse(val gold: Int, val club: ClubDto)
/** Readiness for a live match: energy of the eleven (0–100), whether they can start, minutes until they can, gold for an instant rest. */
@Serializable data class LiveReadyDto(val energy: Int, val ready: Boolean, val restMinutes: Int, val tired: Int, val restGold: Int)
fun com.matchdayclub.domain.usecase.live.LiveReadiness.toDto() = LiveReadyDto(energy, ready, restMinutes, tired, restGold)
@Serializable data class CollectResponse(val building: BuildingDto, val coins: Long, val club: ClubDto)

fun Club.toDto() = ClubDto(id.toString(), name, primaryColor, secondaryColor, emblemJson, level, reputation, ClubLevels.reputationForNext(level), coins, gold, timezone, academyNextAt?.toString(), medicalNextAt?.toString(), streak, loginDay, builderSlots, division, seasonNo, kit, emblemFrame, celebration, trophies, com.matchdayclub.domain.rules.Arenas.of(trophies).key, liveStreak)

fun Club.adsState(now: Instant): AdsStateDto {
    val cooldown = com.matchdayclub.domain.rules.Economy.DOUBLE_INCOME_COOLDOWN_SECONDS
    val today = now.atZone(java.time.ZoneId.of(timezone)).toLocalDate()
    return AdsStateDto(doubleIncomeUntil?.takeIf { it.isAfter(now) }?.toString(), doubleIncomeAdAt?.plusSeconds(cooldown)?.takeIf { it.isAfter(now) }?.toString(), freeSpeedupDate != today)
}

fun LevelSpec.toDto(level: Int) = LevelSpecDto(level, cost, buildSeconds, requiredStadium, requiredTrophies, incomePerHour, capHours, effect, effect2)
fun BuildingSpec.toDto(): BuildingSpecDto {
    val plot = ReservedPlots.of(type)
    return BuildingSpecDto(type.name, width, height, maxPerClub, enabled, levels.mapIndexed { i, l -> l.toDto(i + 1) }, counts, (1..Stadium.MAX_LEVEL).map { capAt(it) }, unlockStadium,
        plotX = plot?.plot?.x ?: 0, plotY = plot?.plot?.y ?: 0, plotW = plot?.plot?.w ?: 0, plotH = plot?.plot?.h ?: 0, courtX = plot?.courtX ?: 0, courtY = plot?.courtY ?: 0)
}

fun Building.toDto(now: Instant, clubLevel: Int, atmosphere: com.matchdayclub.domain.rules.Atmosphere.Bonus = com.matchdayclub.domain.rules.Atmosphere.Bonus()): BuildingDto {
    val spec = BuildingCatalog.level(type, level)
    val remaining = if (state == BuildingState.UPGRADING && upgradeEndsAt != null) maxOf(0, upgradeEndsAt.epochSecond - now.epochSecond) else 0
    val pending = if (state == BuildingState.IDLE) BuildingCatalog.income(spec, now.epochSecond - lastCollectedAt.epochSecond, clubLevel, atmosphere.total) else 0
    val next = if (BuildingCatalog.hasLevel(type, level + 1)) BuildingCatalog.level(type, level + 1).toDto(level + 1) else null
    val rate = if (atmosphere.total > 0) (spec.incomePerHour * (1.0 + atmosphere.total / 100.0)).toLong() else spec.incomePerHour
    return BuildingDto(id.toString(), type.name, level, gridX, gridY, rotation, state.name, upgradeEndsAt?.toString(), remaining, BuildingCatalog.speedUpGold(remaining), pending, rate, spec.capHours, next, variant,
        atmosphere.total, atmosphere.decor, atmosphere.stadium, atmosphere.parking, atmosphere.fence, atmosphere.museum, atmosphere.collection, atmosphere.event)
}

fun com.matchdayclub.domain.model.Obstacle.toDto(now: Instant, stadium: Int): ObstacleDto {
    val remaining = if (state == com.matchdayclub.domain.model.ObstacleState.CLEARING && clearEndsAt != null) maxOf(0, clearEndsAt.epochSecond - now.epochSecond) else 0
    return ObstacleDto(id.toString(), kind.name, gridX, gridY, kind.width, kind.height, state.name, remaining, com.matchdayclub.domain.rules.Obstacles.cost(kind, stadium), kind.seconds,
        BuildingCatalog.speedUpGold(remaining), if (state == com.matchdayclub.domain.model.ObstacleState.READY) rewardGold else 0)
}

fun com.matchdayclub.domain.usecase.club.LayoutSlot.toDto() = LayoutSlotDto(slot, name, placements.map { PlacementDto(it.id.toString(), it.x, it.y, it.rotation) }, updatedAt.toString())
