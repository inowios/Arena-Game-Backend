package com.matchdayclub.domain.rules

import kotlin.math.ceil
import kotlin.math.min

enum class BuildingType { PITCH, BASKETBALL_COURT, TENNIS_COURT, ACADEMY, TRAINING_CENTER, MEDICAL_CENTER, TICKET_OFFICE, CLUB_SHOP, CAFE, PARKING, DECOR }

/**
 * One level of a building. [effect] meaning depends on type:
 * PITCH = athlete attribute cap, ACADEMY = min quality (max = effect + 15), TRAINING_CENTER = slots,
 * MEDICAL_CENTER = heals per cooldown; [effect2]: PITCH = seats, TRAINING_CENTER = attribute delta per training,
 * MEDICAL_CENTER = cooldown hours.
 */
data class LevelSpec(
    val cost: Long,
    val buildSeconds: Long,
    val requiredClubLevel: Int,
    val incomePerHour: Long = 0,
    val capHours: Int = 0,
    val effect: Int = 0,
    val effect2: Int = 0,
)

data class BuildingSpec(val type: BuildingType, val width: Int, val height: Int, val maxPerClub: Int, val enabled: Boolean, val levels: List<LevelSpec>) {
    val maxLevel get() = levels.size
}

object BuildingCatalog {
    private const val H = 3600L
    private const val M = 60L

    val specs: Map<BuildingType, BuildingSpec> = listOf(
        BuildingSpec(BuildingType.PITCH, 8, 6, 1, true, listOf(
            LevelSpec(0, 0, 1, effect = 60, effect2 = 500),
            LevelSpec(2_000, 2 * H, 3, effect = 75, effect2 = 2_000),
            LevelSpec(12_000, 12 * H, 6, effect = 90, effect2 = 8_000),
        )),
        BuildingSpec(BuildingType.BASKETBALL_COURT, 3, 2, 1, false, listOf(LevelSpec(1_500, H, 4, effect = 60), LevelSpec(6_000, 6 * H, 6, effect = 75), LevelSpec(20_000, 16 * H, 8, effect = 90))),
        BuildingSpec(BuildingType.TENNIS_COURT, 3, 2, 1, false, listOf(LevelSpec(1_500, H, 4, effect = 60), LevelSpec(6_000, 6 * H, 6, effect = 75), LevelSpec(20_000, 16 * H, 8, effect = 90))),
        BuildingSpec(BuildingType.ACADEMY, 2, 2, 1, true, listOf(
            LevelSpec(500, 10 * M, 1, effect = 40), LevelSpec(3_000, 4 * H, 3, effect = 50), LevelSpec(15_000, 16 * H, 6, effect = 60),
        )),
        BuildingSpec(BuildingType.TRAINING_CENTER, 2, 2, 1, true, listOf(
            LevelSpec(800, 20 * M, 1, effect = 1, effect2 = 1), LevelSpec(4_000, 6 * H, 3, effect = 2, effect2 = 2), LevelSpec(18_000, 20 * H, 6, effect = 3, effect2 = 3),
        )),
        BuildingSpec(BuildingType.MEDICAL_CENTER, 2, 2, 1, true, listOf(
            LevelSpec(600, 15 * M, 2, effect = 1, effect2 = 4), LevelSpec(3_500, 5 * H, 4, effect = 2, effect2 = 3), LevelSpec(16_000, 18 * H, 7, effect = 3, effect2 = 2),
        )),
        BuildingSpec(BuildingType.TICKET_OFFICE, 2, 1, 1, true, listOf(
            LevelSpec(300, 5 * M, 1, incomePerHour = 120, capHours = 6), LevelSpec(2_000, 3 * H, 2, incomePerHour = 300, capHours = 8), LevelSpec(9_000, 10 * H, 5, incomePerHour = 700, capHours = 12),
        )),
        BuildingSpec(BuildingType.CLUB_SHOP, 2, 2, 1, true, listOf(
            LevelSpec(1_000, 30 * M, 2, incomePerHour = 200, capHours = 6), LevelSpec(5_000, 6 * H, 4, incomePerHour = 500, capHours = 8), LevelSpec(20_000, 20 * H, 7, incomePerHour = 1_100, capHours = 12),
        )),
        BuildingSpec(BuildingType.CAFE, 2, 2, 1, true, listOf(
            LevelSpec(700, 20 * M, 2, incomePerHour = 150, capHours = 6), LevelSpec(3_500, 5 * H, 3, incomePerHour = 380, capHours = 8), LevelSpec(14_000, 16 * H, 6, incomePerHour = 850, capHours = 12),
        )),
        BuildingSpec(BuildingType.PARKING, 3, 2, 1, true, listOf(
            LevelSpec(1_500, 45 * M, 3, incomePerHour = 250, capHours = 8), LevelSpec(7_000, 8 * H, 5, incomePerHour = 600, capHours = 10), LevelSpec(25_000, 24 * H, 8, incomePerHour = 1_300, capHours = 14),
        )),
        BuildingSpec(BuildingType.DECOR, 1, 1, Int.MAX_VALUE, true, listOf(LevelSpec(200, 0, 1))),
    ).associateBy { it.type }

    fun spec(type: BuildingType): BuildingSpec = specs.getValue(type)
    fun level(type: BuildingType, level: Int): LevelSpec = spec(type).levels[level - 1]
    fun hasLevel(type: BuildingType, level: Int) = level in 1..spec(type).maxLevel

    /** Coins earned since the last collection, capped by the building's storage. */
    fun income(spec: LevelSpec, secondsSinceCollect: Long, clubLevel: Int): Long {
        if (spec.incomePerHour == 0L) return 0
        val hours = min(secondsSinceCollect / 3600.0, spec.capHours.toDouble())
        val multiplier = 1.0 + (clubLevel - 1) * 0.05
        return (spec.incomePerHour * hours * multiplier).toLong()
    }

    /** 1 gold per started 10 minutes, minimum 1. */
    fun speedUpGold(remainingSeconds: Long): Int = if (remainingSeconds <= 0) 0 else maxOf(1, ceil(remainingSeconds / 600.0).toInt())
}

object ClubLevels {
    private val thresholds = listOf(0, 100, 300, 700, 1_500, 3_000, 5_500, 9_000, 14_000, 20_000)
    const val MAX_LEVEL = 10
    fun levelFor(reputation: Int): Int = thresholds.indexOfLast { reputation >= it } + 1
    fun gridSize(level: Int): Int = min(12 + (level - 1) * 2, 30)
    fun reputationForNext(level: Int): Int? = thresholds.getOrNull(level)
}

data class Footprint(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun fits(gridSize: Int) = x >= 0 && y >= 0 && x + w <= gridSize && y + h <= gridSize
    fun overlaps(o: Footprint) = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
}

fun footprint(type: BuildingType, x: Int, y: Int, rotation: Int): Footprint {
    val s = BuildingCatalog.spec(type)
    return if (rotation % 180 == 0) Footprint(x, y, s.width, s.height) else Footprint(x, y, s.height, s.width)
}
