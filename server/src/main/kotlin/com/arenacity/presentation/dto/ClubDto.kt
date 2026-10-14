package com.arenacity.presentation.dto

import com.arenacity.common.i18n.ServerI18n
import com.arenacity.domain.model.Building
import com.arenacity.domain.model.BuildingState
import com.arenacity.domain.model.Club
import com.arenacity.domain.rules.BuildingCatalog
import com.arenacity.domain.rules.BuildingSpec
import com.arenacity.domain.rules.ClubLevels
import com.arenacity.domain.rules.LandMap
import com.arenacity.domain.rules.LandSet
import com.arenacity.domain.rules.LevelSpec
import com.arenacity.domain.rules.Sections
import com.arenacity.domain.rules.Stadium
import com.arenacity.domain.rules.Zones
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
/**
 * [counts]/[caps]: copies allowed and highest level allowed by stadium level (index 0 = stadium 1); [unlockStadium]: first
 * stadium level with a copy. The sport town (14 October): [island] = the zone sport whose side island the building stands
 * on (an island arena; null = anywhere on the club's land), [section] = the sport section it opens (the stadium and the
 * basketball and tennis arenas), [soon] = no mechanic yet (buildable and upgradable; [about] says what it will do,
 * [soonText] is «soon»); [name] and [about] in the caller's language (Accept-Language).
 */
@Serializable data class BuildingSpecDto(val type: String, val width: Int, val height: Int, val maxPerClub: Int, val enabled: Boolean, val levels: List<LevelSpecDto>,
                                         val counts: List<Int> = emptyList(), val caps: List<Int> = emptyList(), val unlockStadium: Int = 1,
                                         val name: String = "", val island: String? = null, val section: String? = null, val soon: Boolean = false,
                                         val about: String? = null, val soonText: String? = null)

@Serializable data class BuildingDto(
    val id: String, val type: String, val level: Int, val x: Int, val y: Int, val rotation: Int, val state: String,
    val upgradeEndsAt: String?, val remainingSeconds: Long, val speedUpGold: Int, val pendingIncome: Long, val incomePerHour: Long, val capHours: Int,
    val nextLevel: LevelSpecDto?,
    val variant: String? = null,
    /**
     * Atmosphere bonus to income in % (total and its parts: decor around, next to the stadium, the old car park — 0 since
     * the sport town —, fences, the hall of fame, complete sets, a live event, a yard court nearby).
     */
    val bonusPct: Int = 0, val bonusDecor: Int = 0, val bonusStadium: Int = 0, val bonusParking: Int = 0, val bonusFence: Int = 0,
    val bonusMuseum: Int = 0, val bonusCollection: Int = 0, val bonusEvent: Int = 0, val bonusCourt: Int = 0,
)

@Serializable data class ClubStateResponse(val club: ClubDto, val buildings: List<BuildingDto>, val gridSize: Int, val serverTime: String, val sections: List<String> = listOf("FOOTBALL"), val ads: AdsStateDto? = null, val inboxUnread: Int = 0, val live: LiveReadyDto? = null,
                                          /** The stadium level whose limits apply now (the club's town hall). */ val stadium: Int = 1,
                                          val obstacles: List<ObstacleDto> = emptyList(),
                                          /** Deprecated, for one client release: the old square plot's parcels (the 4×4 home parcels of the 6×6 field), see [LandDto]. */
                                          val parcels: List<ParcelDto> = emptyList(),
                                          /** The club's land: the land mask's id and regions (see [LandDto]). */
                                          val land: LandDto? = null)
@Serializable data class ParcelDto(val col: Int, val row: Int)
/**
 * The club's land since the owner's picture (13 October, docs/art/PERSPECTIVE-MAP.md §5.5 in the mobile repo): [maskId] =
 * the sha1 id of the land mask (GET /catalog/land; the client compares its bundled copy), [regions] = the ids of the
 * regions the club builds on (the main island; a side island once its arena opens it), [locked] = the side islands
 * still locked. [parcel]/[parcels]/[nextPrice]/[buyable] are the old parcel field's, kept for one client release (land
 * is not for sale: 0 and false).
 */
@Serializable data class LandDto(val parcel: Int = LEGACY_PARCEL, val parcels: Int = LEGACY_PARCELS, val nextPrice: Long = 0, val buyable: Boolean = false,
                                 val maskId: String = "", val regions: List<String> = emptyList(), val locked: List<LockedLandDto> = emptyList())
/**
 * A side island not open for building: its region [id], the [zone] sport whose arena opens it, the stadium level that
 * arena needs; [state] LOCKED (its arena is not open yet: not bought, or still being restored — GET /club/zones has
 * which). A zone that is OPEN opens its island: it is in [LandDto.regions] then, not here.
 */
@Serializable data class LockedLandDto(val id: String, val zone: String?, val requiredStadium: Int, val state: String = "LOCKED")

private const val LEGACY_PARCEL = 12
private const val LEGACY_PARCELS = 6
/** The old plot's parcels (cells -9..38 = parcels 1..4 of the 6×6 field), sent unchanged until the client reads the mask. */
val LEGACY_HOME_PARCELS: List<ParcelDto> = (1..4).flatMap { c -> (1..4).map { r -> ParcelDto(c, r) } }

fun LandSet.toDto(lands: LandMap) = LandDto(maskId = lands.id, regions = ids,
    locked = lands.locked(this).map { r -> LockedLandDto(r.id, r.lock, r.sport?.let { Zones.spec(it).requiredStadium } ?: 0) })
/** An obstacle on the plot: [cost]/[seconds] to clear it; [rewardGold] only once it is cleared (READY). */
@Serializable data class ObstacleDto(val id: String, val kind: String, val x: Int, val y: Int, val w: Int, val h: Int, val state: String, val remainingSeconds: Long,
                                     val cost: Long, val seconds: Long, val speedUpGold: Int, val rewardGold: Int)
@Serializable data class ObstacleLootResponse(val gold: Int, val club: ClubDto)
/** Readiness for a live match: energy of the eleven (0–100), whether they can start, minutes until they can, gold for an instant rest. */
@Serializable data class LiveReadyDto(val energy: Int, val ready: Boolean, val restMinutes: Int, val tired: Int, val restGold: Int)
fun com.arenacity.domain.usecase.live.LiveReadiness.toDto() = LiveReadyDto(energy, ready, restMinutes, tired, restGold)
@Serializable data class CollectResponse(val building: BuildingDto, val coins: Long, val club: ClubDto)

fun Club.toDto() = ClubDto(id.toString(), name, primaryColor, secondaryColor, emblemJson, level, reputation, ClubLevels.reputationForNext(level), coins, gold, timezone, academyNextAt?.toString(), medicalNextAt?.toString(), streak, loginDay, builderSlots, division, seasonNo, kit, emblemFrame, celebration, trophies, com.arenacity.domain.rules.Arenas.of(trophies).key, liveStreak)

fun Club.adsState(now: Instant): AdsStateDto {
    val cooldown = com.arenacity.domain.rules.Economy.DOUBLE_INCOME_COOLDOWN_SECONDS
    val today = now.atZone(java.time.ZoneId.of(timezone)).toLocalDate()
    return AdsStateDto(doubleIncomeUntil?.takeIf { it.isAfter(now) }?.toString(), doubleIncomeAdAt?.plusSeconds(cooldown)?.takeIf { it.isAfter(now) }?.toString(), freeSpeedupDate != today)
}

fun LevelSpec.toDto(level: Int) = LevelSpecDto(level, cost, buildSeconds, requiredStadium, requiredTrophies, incomePerHour, capHours, effect, effect2)
fun BuildingSpec.toDto(lang: String = "en") = BuildingSpecDto(type.name, width, height, maxPerClub, enabled, levels.mapIndexed { i, l -> l.toDto(i + 1) }, counts, (1..Stadium.MAX_LEVEL).map { capAt(it) }, unlockStadium,
    ServerI18n.t("building.${type.name}", lang), island?.name, Sections.sportOfVenue(type)?.name, soon,
    "building.${type.name}.about".takeIf { ServerI18n.has(it) }?.let { ServerI18n.t(it, lang) }, if (soon) ServerI18n.t("building.soon", lang) else null)

fun Building.toDto(now: Instant, clubLevel: Int, atmosphere: com.arenacity.domain.rules.Atmosphere.Bonus = com.arenacity.domain.rules.Atmosphere.Bonus()): BuildingDto {
    val spec = BuildingCatalog.level(type, level)
    val remaining = if (state == BuildingState.UPGRADING && upgradeEndsAt != null) maxOf(0, upgradeEndsAt.epochSecond - now.epochSecond) else 0
    val pending = if (state == BuildingState.IDLE) BuildingCatalog.income(spec, now.epochSecond - lastCollectedAt.epochSecond, clubLevel, atmosphere.total) else 0
    val next = if (BuildingCatalog.hasLevel(type, level + 1)) BuildingCatalog.level(type, level + 1).toDto(level + 1) else null
    val rate = if (atmosphere.total > 0) (spec.incomePerHour * (1.0 + atmosphere.total / 100.0)).toLong() else spec.incomePerHour
    return BuildingDto(id.toString(), type.name, level, gridX, gridY, rotation, state.name, upgradeEndsAt?.toString(), remaining, BuildingCatalog.speedUpGold(remaining), pending, rate, spec.capHours, next, variant,
        atmosphere.total, atmosphere.decor, atmosphere.stadium, atmosphere.parking, atmosphere.fence, atmosphere.museum, atmosphere.collection, atmosphere.event, atmosphere.court)
}

fun com.arenacity.domain.model.Obstacle.toDto(now: Instant, stadium: Int): ObstacleDto {
    val remaining = if (state == com.arenacity.domain.model.ObstacleState.CLEARING && clearEndsAt != null) maxOf(0, clearEndsAt.epochSecond - now.epochSecond) else 0
    return ObstacleDto(id.toString(), kind.name, gridX, gridY, kind.width, kind.height, state.name, remaining, com.arenacity.domain.rules.Obstacles.cost(kind, stadium), kind.seconds,
        BuildingCatalog.speedUpGold(remaining), if (state == com.arenacity.domain.model.ObstacleState.READY) rewardGold else 0)
}

fun com.arenacity.domain.usecase.club.LayoutSlot.toDto() = LayoutSlotDto(slot, name, placements.map { PlacementDto(it.id.toString(), it.x, it.y, it.rotation) }, updatedAt.toString())
