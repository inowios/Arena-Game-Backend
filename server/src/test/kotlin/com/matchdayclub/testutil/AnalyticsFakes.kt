package com.matchdayclub.testutil

import com.matchdayclub.domain.model.AnalyticsEvent
import com.matchdayclub.domain.model.ClubSnapshot
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
    var buildings: Map<String, Map<Int, Int>> = emptyMap()
    var clubs: List<ClubSnapshot> = emptyList()

    override suspend fun markActive(userId: UUID, day: LocalDate): Boolean { markCalls++; return days.add(userId to day) }
    override suspend fun record(events: List<AnalyticsEvent>) { this.events += events }
    override suspend fun deleteEventsOf(userId: UUID) { events.removeAll { it.userId == userId } }
    override suspend fun purgeEventsBefore(before: Instant): Int { val n = events.count { it.at.isBefore(before) }; events.removeAll { it.at.isBefore(before) }; return n }
    override suspend fun deleteActivityOf(userId: UUID) { days.removeAll { it.first == userId } }
    override suspend fun purgeActivityBefore(day: LocalDate): Int { val n = days.count { it.second.isBefore(day) }; days.removeAll { it.second.isBefore(day) }; return n }
    override suspend fun cohorts(from: LocalDate, to: LocalDate) = cohortRows.filter { !it.day.isBefore(from) && !it.day.isAfter(to) }
    override suspend fun activeUsers(from: LocalDate, to: LocalDate) = days.filter { !it.second.isBefore(from) && !it.second.isAfter(to) }.map { it.first }.toSet().size
    override suspend fun privateLeagueMembership(from: LocalDate, to: LocalDate) = privateLeague
    override suspend fun invitedNewPlayers(from: Instant, to: Instant) = invited
    override suspend fun purchases(from: Instant, to: Instant) = mapOf("gold_small" to 2)
    override suspend fun adRewards(from: Instant, to: Instant) = 5
    override suspend fun eventCounts(from: Instant, to: Instant, limit: Int) =
        events.filter { !it.at.isBefore(from) && it.at.isBefore(to) }.groupingBy { it.name }.eachCount()
    override suspend fun cohortEvents(from: Instant, to: Instant, horizon: Instant, names: Set<String>, limit: Int): List<AnalyticsEvent> {
        val cohort = events.filter { it.name == "club_created" && !it.at.isBefore(from) && it.at.isBefore(to) }.mapNotNull { it.userId }.toSet()
        return events.filter { it.name in names && it.userId in cohort && !it.at.isBefore(from) && it.at.isBefore(horizon) }.sortedBy { it.at }.take(limit)
    }
    override suspend fun eventProps(name: String, from: Instant, to: Instant, limit: Int) = events.filter { it.name == name && !it.at.isBefore(from) && it.at.isBefore(to) }.map { it.props }.take(limit)
    override suspend fun buildingLevels() = buildings
    override suspend fun activeClubs(from: LocalDate, to: LocalDate, limit: Int) = clubs
}
