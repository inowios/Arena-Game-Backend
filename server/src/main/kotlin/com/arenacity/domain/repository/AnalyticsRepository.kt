package com.arenacity.domain.repository

import com.arenacity.domain.model.AnalyticsEvent
import com.arenacity.domain.model.ClubSnapshot
import com.arenacity.domain.model.CohortRow
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface AnalyticsRepository {
    /** Records that [userId] was active on [day] (UTC). Returns false when it was already recorded. */
    suspend fun markActive(userId: UUID, day: LocalDate): Boolean
    suspend fun record(events: List<AnalyticsEvent>)
    suspend fun deleteEventsOf(userId: UUID)
    suspend fun deleteActivityOf(userId: UUID)
    /** Retention: drops events older than [before]. @return rows deleted. */
    suspend fun purgeEventsBefore(before: Instant): Int
    /** Retention: drops activity days before [day]. @return rows deleted. */
    suspend fun purgeActivityBefore(day: LocalDate): Int

    /** Human signups per UTC day in [from, to] with the number of them active on day+1 and day+7. */
    suspend fun cohorts(from: LocalDate, to: LocalDate): List<CohortRow>
    /** Distinct users active on any day in [from, to]. */
    suspend fun activeUsers(from: LocalDate, to: LocalDate): Int
    /** (users active in [from, to], of them with a club in at least one private league). */
    suspend fun privateLeagueMembership(from: LocalDate, to: LocalDate): Pair<Int, Int>
    /** Distinct new players who joined through an invite in [from, to). */
    suspend fun invitedNewPlayers(from: Instant, to: Instant): Int
    /** Completed purchases by product id in [from, to). */
    suspend fun purchases(from: Instant, to: Instant): Map<String, Int>
    suspend fun adRewards(from: Instant, to: Instant): Int
    /** The most frequent event names in [from, to). */
    suspend fun eventCounts(from: Instant, to: Instant, limit: Int = 40): Map<String, Int>

    /** Events named in [names] of the users whose club_created falls in [from, to), up to [horizon] (onboarding funnel). */
    suspend fun cohortEvents(from: Instant, to: Instant, horizon: Instant, names: Set<String>, limit: Int = 200_000): List<AnalyticsEvent>
    /** The props of every [name] event in [from, to). */
    suspend fun eventProps(name: String, from: Instant, to: Instant, limit: Int = 100_000): List<Map<String, String>>
    /** Buildings of human clubs per type and level. */
    suspend fun buildingLevels(): Map<String, Map<Int, Int>>
    /** The clubs of humans active on any day in [from, to]. */
    suspend fun activeClubs(from: LocalDate, to: LocalDate, limit: Int = 50_000): List<ClubSnapshot>
}
