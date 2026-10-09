package com.matchdayclub.testutil

import com.matchdayclub.domain.model.AnalyticsEvent
import com.matchdayclub.domain.model.CohortRow
import com.matchdayclub.domain.repository.AnalyticsRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** In-memory analytics: activity days, events and canned report inputs. */
class FakeAnalyticsRepository : AnalyticsRepository {
    val days = mutableSetOf<Pair<UUID, LocalDate>>()
    val events = mutableListOf<AnalyticsEvent>()
    var cohortRows: List<CohortRow> = emptyList()
    var privateLeague: Pair<Int, Int> = 0 to 0
    var invited = 0
    var markCalls = 0

    override suspend fun markActive(userId: UUID, day: LocalDate): Boolean { markCalls++; return days.add(userId to day) }
    override suspend fun record(events: List<AnalyticsEvent>) { this.events += events }
    override suspend fun deleteEventsOf(userId: UUID) { events.removeAll { it.userId == userId } }
    override suspend fun cohorts(from: LocalDate, to: LocalDate) = cohortRows.filter { !it.day.isBefore(from) && !it.day.isAfter(to) }
    override suspend fun activeUsers(from: LocalDate, to: LocalDate) = days.filter { !it.second.isBefore(from) && !it.second.isAfter(to) }.map { it.first }.toSet().size
    override suspend fun privateLeagueMembership(from: LocalDate, to: LocalDate) = privateLeague
    override suspend fun invitedNewPlayers(from: Instant, to: Instant) = invited
    override suspend fun purchases(from: Instant, to: Instant) = mapOf("gold_small" to 2)
    override suspend fun adRewards(from: Instant, to: Instant) = 5
    override suspend fun eventCounts(from: Instant, to: Instant, limit: Int) =
        events.filter { !it.at.isBefore(from) && it.at.isBefore(to) }.groupingBy { it.name }.eachCount()
}
