package com.matchdayclub.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class LeagueKind { PUBLIC, PRIVATE }
enum class LeagueStatus { ACTIVE, FINISHED }
enum class FixtureKind { LEAGUE, FRIENDLY, CHALLENGE, CUP }
enum class FixtureStatus { SCHEDULED, PLAYED }

data class League(
    val id: UUID,
    val kind: LeagueKind,
    val name: String,
    val code: String?,
    val division: Int,
    val tzBucket: Int,
    val timezone: String,
    val matchdayHour: Int,
    val seasonNumber: Int,
    val seasonStart: LocalDate,
    val currentRound: Int,
    val createdBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val status: LeagueStatus = LeagueStatus.ACTIVE,
)

data class Fixture(
    val id: UUID,
    val leagueId: UUID?,
    val kind: FixtureKind,
    val seasonNumber: Int,
    val round: Int,
    val scheduledAt: Instant,
    val homeClubId: UUID,
    val awayClubId: UUID,
    val status: FixtureStatus,
    val homeScore: Int?,
    val awayScore: Int?,
    val seed: Long,
    val resultJson: String?,
    val playedAt: Instant?,
    val createdAt: Instant,
    /** Table points earned in this fixture (sum over sections); null until played. */
    val homePoints: Int? = null,
    val awayPoints: Int? = null,
) {
    fun involves(clubId: UUID) = homeClubId == clubId || awayClubId == clubId
    fun pointsFor(clubId: UUID): Int? = if (clubId == homeClubId) homePoints else if (clubId == awayClubId) awayPoints else null
}
