package com.matchdayclub.domain.model

import com.matchdayclub.domain.rules.BuildingType
import java.time.Instant
import java.util.UUID

data class Club(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val primaryColor: String,
    val secondaryColor: String,
    val emblemJson: String?,
    val level: Int,
    val reputation: Int,
    val coins: Long,
    val gold: Int,
    val timezone: String,
    val academyNextAt: Instant?,
    val medicalNextAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val isBot: Boolean = false,
    val division: Int = 1,
    val tzBucket: Int = 0,
    val friendliesDate: java.time.LocalDate? = null,
    val friendliesPlayed: Int = 0,
    val loginDay: Int = 0,
    val loginLast: java.time.LocalDate? = null,
    val streak: Int = 0,
    val doubleIncomeUntil: Instant? = null,
    val doubleIncomeAdAt: Instant? = null,
    val freeSpeedupDate: java.time.LocalDate? = null,
    val builderSlots: Int = 1,
    /** The club's own season counter (1 = first season); keys the season pass and history. */
    val seasonNo: Int = 1,
)

enum class BuildingState { IDLE, UPGRADING }

data class Building(
    val id: UUID,
    val clubId: UUID,
    val type: BuildingType,
    val level: Int,
    val gridX: Int,
    val gridY: Int,
    val rotation: Int,
    val state: BuildingState,
    val upgradeEndsAt: Instant?,
    val lastCollectedAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun isDone(now: Instant) = state == BuildingState.UPGRADING && upgradeEndsAt != null && !now.isBefore(upgradeEndsAt)
}
