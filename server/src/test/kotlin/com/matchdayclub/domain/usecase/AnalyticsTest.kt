package com.matchdayclub.domain.usecase

import com.matchdayclub.core.metrics.Metrics
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.CohortRow
import com.matchdayclub.domain.usecase.analytics.*
import com.matchdayclub.presentation.dto.toDto
import com.matchdayclub.testutil.FakeAnalyticsRepository
import com.matchdayclub.testutil.FixedClock
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*

class AnalyticsTest {
    private val clock = FixedClock(Instant.parse("2026-10-20T12:00:00Z"))
    private val repo = FakeAnalyticsRepository()

    @Test
    fun `activity is stored once per user and UTC day`() = runBlocking<Unit> {
        val tracker = ActivityTracker(repo, clock)
        val u = UUID.randomUUID()
        repeat(5) { tracker.seen(u) }
        assertEquals(1, repo.markCalls)
        clock.advanceSeconds(13 * 3600)   // 01:00 UTC next day
        tracker.seen(u)
        assertEquals(setOf(u to LocalDate.parse("2026-10-20"), u to LocalDate.parse("2026-10-21")), repo.days)
    }

    @Test
    fun `client events are cleaned, invalid names dropped, batches limited`() = runBlocking<Unit> {
        val track = TrackEventsUseCase(repo, clock)
        val u = UUID.randomUUID()
        val n = track(u, listOf(
            ClientEvent("tutorial_step", mapOf("step" to "build", "Bad Key" to "x", "long" to "y".repeat(300))),
            ClientEvent("DROP TABLE"), ClientEvent("store_open", at = clock.now().minusSeconds(3600).toEpochMilli()),
            ClientEvent("ad_show", at = clock.now().minusSeconds(10 * 86_400).toEpochMilli()),   // too old: dated now
        ), "Android", "1.2.3")
        assertEquals(3, n)
        val step = repo.events.first { it.name == "tutorial_step" }
        assertEquals(mapOf("step" to "build", "long" to "y".repeat(Analytics.VALUE_MAX)), step.props)
        assertEquals("android", step.platform); assertEquals("1.2.3", step.appVersion)
        assertEquals(clock.now().minusSeconds(3600), repo.events.first { it.name == "store_open" }.at)
        assertEquals(clock.now(), repo.events.first { it.name == "ad_show" }.at)
        assertFailsWith<ValidationException> { track(u, List(Analytics.BATCH_MAX + 1) { ClientEvent("session_start") }, null, null) }
    }

    @Test
    fun `KPI report uses only cohorts whose return day is complete`() = runBlocking<Unit> {
        val today = LocalDate.parse("2026-10-20")
        repo.cohortRows = listOf(
            CohortRow(today.minusDays(9), 10, 5, 2),    // D1 and D7 complete
            CohortRow(today.minusDays(3), 10, 3, 0),    // D1 complete, D7 not yet
            CohortRow(today.minusDays(1), 10, 9, 0),    // its day 1 is today: not counted
        )
        val users = List(10) { UUID.randomUUID() }
        users.forEach { repo.days += it to today }
        users.take(4).forEach { repo.days += it to today.minusDays(10) }
        repo.privateLeague = 10 to 4; repo.invited = 3
        repo.events += com.matchdayclub.domain.model.AnalyticsEvent(null, "session_start", at = clock.now())
        val r = MetricsReportUseCase(repo, clock)(30)
        assertEquals(LocalDate.parse("2026-09-21"), r.from); assertEquals(today, r.to)
        assertEquals(0.4, r.d1)          // (5 + 3) / 20
        assertEquals(0.2, r.d7)          // 2 / 10
        assertEquals(10, r.dau); assertEquals(10, r.wau); assertEquals(10, r.mau); assertEquals(10, r.activeInPeriod)
        assertEquals(0.4, r.privateLeagueShare); assertEquals(0.3, r.kFactor)
        val dto = r.toDto()
        assertEquals("ON_TARGET", dto.kpis.getValue("d1Retention").status)
        assertEquals("ON_TARGET", dto.kpis.getValue("d7Retention").status)
        assertEquals("ON_TARGET", dto.kpis.getValue("privateLeagueShare").status)
        assertEquals("ON_TARGET", dto.kpis.getValue("kFactor").status)
        assertEquals(30, dto.installs); assertEquals(1, dto.events["session_start"])
        assertFailsWith<ValidationException> { MetricsReportUseCase(repo, clock)(0) }
    }

    @Test
    fun `no data and below-threshold statuses`() = runBlocking<Unit> {
        val r = MetricsReportUseCase(repo, clock)(7)
        assertNull(r.d1); assertNull(r.kFactor)
        assertEquals("NO_DATA", r.toDto().kpis.getValue("d1Retention").status)
        repo.cohortRows = listOf(CohortRow(LocalDate.parse("2026-10-10"), 100, 25, 11))
        repo.days += UUID.randomUUID() to LocalDate.parse("2026-10-20")
        val low = MetricsReportUseCase(repo, clock)(30).toDto().kpis
        assertEquals("BELOW_THRESHOLD", low.getValue("d1Retention").status)   // 25% < 30%
        assertEquals("BELOW_THRESHOLD", low.getValue("d7Retention").status)   // 11% < 12%
    }

    @Test
    fun `old events are purged once a day`() = runBlocking<Unit> {
        val retention = AnalyticsRetention(repo, clock)
        repo.events += com.matchdayclub.domain.model.AnalyticsEvent(null, "old", at = clock.now().minusSeconds((Analytics.RETENTION_DAYS + 1) * 86_400))
        repo.events += com.matchdayclub.domain.model.AnalyticsEvent(null, "recent", at = clock.now().minusSeconds(86_400))
        val u = UUID.randomUUID(); repo.days += u to LocalDate.parse("2025-01-01"); repo.days += u to LocalDate.parse("2026-10-19")
        assertEquals(2, retention.runIfDue())   // one event + one activity day
        assertEquals(listOf("recent"), repo.events.map { it.name }); assertEquals(setOf(u to LocalDate.parse("2026-10-19")), repo.days)
        repo.events += com.matchdayclub.domain.model.AnalyticsEvent(null, "old", at = clock.now().minusSeconds((Analytics.RETENTION_DAYS + 5) * 86_400))
        assertEquals(0, retention.runIfDue())                        // not again the same day
        clock.advanceSeconds(86_401); assertEquals(1, retention.runIfDue())
    }

    @Test
    fun `prometheus exposition`() {
        Metrics.counter("test_things_total", "Things")
        Metrics.inc("test_things_total", "kind" to "a\"b")
        Metrics.inc("test_things_total", "kind" to "a\"b", by = 2)
        Metrics.histogram("test_latency_seconds", "Latency")
        Metrics.observe("test_latency_seconds", 0.02); Metrics.observe("test_latency_seconds", 3.0); Metrics.observe("test_latency_seconds", 60.0)
        Metrics.gauge("test_gauge", "A gauge") { 7.0 }
        val text = Metrics.render()
        assertTrue(text.contains("# TYPE test_things_total counter\n"))
        assertTrue(text.contains("test_things_total{kind=\"a\\\"b\"} 3\n"), text)
        assertTrue(text.contains("test_latency_seconds_bucket{le=\"0.025\"} 1\n"))
        assertTrue(text.contains("test_latency_seconds_bucket{le=\"5\"} 2\n"))
        assertTrue(text.contains("test_latency_seconds_bucket{le=\"+Inf\"} 3\n"))
        assertTrue(text.contains("test_latency_seconds_count 3\n"))
        assertTrue(text.contains("# TYPE test_gauge gauge\ntest_gauge 7\n"))
        assertEquals(3, Metrics.count("test_things_total", "kind" to "a\"b"))
    }
}
