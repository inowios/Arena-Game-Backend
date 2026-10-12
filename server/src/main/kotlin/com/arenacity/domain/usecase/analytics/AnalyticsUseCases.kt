package com.arenacity.domain.usecase.analytics

import com.arenacity.common.time.Clock
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.AnalyticsEvent
import com.arenacity.domain.model.Club
import com.arenacity.domain.model.MetricsReport
import com.arenacity.domain.repository.AnalyticsRepository
import com.arenacity.domain.usecase.club.ClubInitializer
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong

private fun utcDay(at: Instant): LocalDate = at.atZone(ZoneOffset.UTC).toLocalDate()

/**
 * Marks users active per UTC day on their authenticated requests (one insert per user and day; the in-memory map keeps
 * the rest of the day's requests off the database).
 */
class ActivityTracker(private val repo: AnalyticsRepository, private val clock: Clock) {
    private val seen = ConcurrentHashMap<UUID, LocalDate>()
    private val log = LoggerFactory.getLogger("ActivityTracker")

    suspend fun seen(userId: UUID) {
        val today = utcDay(clock.now())
        if (seen[userId] == today) return
        try {
            repo.markActive(userId, today)
            if (seen.size > 500_000) seen.clear()
            seen[userId] = today
        } catch (e: Exception) {
            log.debug("activity not recorded", e)
        }
    }
}

/** Keeps product events and activity days for [Analytics.RETENTION_DAYS] (privacy policy); run from the scheduler at most once a day. */
class AnalyticsRetention(private val repo: AnalyticsRepository, private val clock: Clock) {
    @Volatile private var lastRun: Instant? = null
    suspend fun runIfDue(): Int {
        val now = clock.now()
        if (lastRun?.isAfter(now.minusSeconds(86_400)) == true) return 0
        lastRun = now
        val cutoff = now.minusSeconds(Analytics.RETENTION_DAYS * 86_400)
        return repo.purgeEventsBefore(cutoff) + repo.purgeActivityBefore(utcDay(cutoff))
    }
}

/** Server-side product events; never fails the caller. */
class EventLog(private val repo: AnalyticsRepository, private val clock: Clock) {
    private val log = LoggerFactory.getLogger("EventLog")
    suspend fun track(userId: UUID?, name: String, props: Map<String, String> = emptyMap()) {
        try { repo.record(listOf(AnalyticsEvent(userId, name, props.mapValues { it.value.take(Analytics.VALUE_MAX) }, "server", null, clock.now()))) }
        catch (e: Exception) { log.warn("event $name not recorded: ${e.message}") }
    }
}

object Analytics {
    const val BATCH_MAX = 50
    /** Usage events are kept about 13 months, then deleted. */
    const val RETENTION_DAYS = 400L
    const val PROPS_MAX = 12
    const val VALUE_MAX = 100
    private val NAME = Regex("^[a-z][a-z0-9_]{1,39}$")
    private val KEY = Regex("^[a-z][a-z0-9_]{0,23}$")
    private val PLATFORMS = setOf("android", "ios", "editor", "mac", "windows", "linux", "other")
    /** How far back a queued client event may be dated (offline sessions). */
    const val BACKDATE_SECONDS = 3 * 86_400L

    /** A client event, cleaned: null when the name is not an event name. Props are trimmed to the limits. */
    fun clean(userId: UUID, name: String, props: Map<String, String>?, platform: String?, appVersion: String?, atMs: Long?, now: Instant): AnalyticsEvent? {
        if (!NAME.matches(name)) return null
        val p = props.orEmpty().entries.asSequence().filter { KEY.matches(it.key) }.take(PROPS_MAX).associate { it.key to it.value.take(VALUE_MAX) }
        val at = atMs?.let { Instant.ofEpochMilli(it) }?.takeIf { !it.isAfter(now) && it.isAfter(now.minusSeconds(BACKDATE_SECONDS)) } ?: now
        val plat = platform?.lowercase()?.let { if (it in PLATFORMS) it else "other" }
        return AnalyticsEvent(userId, name, p, plat, appVersion?.take(20), at)
    }

    fun rate(part: Int, whole: Int): Double? = if (whole <= 0) null else (part.toDouble() / whole * 10_000).roundToLong() / 10_000.0
}

data class ClientEvent(val name: String, val props: Map<String, String>? = null, val at: Long? = null)

/** POST /analytics/events: a batch from the app (invalid names are dropped, not rejected). */
class TrackEventsUseCase(private val repo: AnalyticsRepository, private val clock: Clock) {
    suspend operator fun invoke(userId: UUID, events: List<ClientEvent>, platform: String?, appVersion: String?): Int {
        if (events.size > Analytics.BATCH_MAX) throw ValidationException("Too many events in one batch")
        val now = clock.now()
        val clean = events.mapNotNull { Analytics.clean(userId, it.name, it.props, platform, appVersion, it.at, now) }
        if (clean.isNotEmpty()) repo.record(clean)
        return clean.size
    }
}

/** The launch KPI report over the last [days] days up to [to] (UTC). */
class MetricsReportUseCase(private val repo: AnalyticsRepository, private val clock: Clock) {
    suspend operator fun invoke(days: Int = 30, to: LocalDate? = null): MetricsReport {
        if (days !in 1..365) throw ValidationException("days must be 1..365")
        val today = utcDay(clock.now())
        val end = to?.coerceAtMost(today) ?: today
        val start = end.minusDays(days - 1L)
        val lastComplete = minOf(end, today.minusDays(1))
        val cohorts = repo.cohorts(start, end)
        fun retention(k: Long, pick: (com.arenacity.domain.model.CohortRow) -> Int): Double? {
            val eligible = cohorts.filter { !it.day.plusDays(k).isAfter(lastComplete) }
            return Analytics.rate(eligible.sumOf(pick), eligible.sumOf { it.installs })
        }
        val activeInPeriod = repo.activeUsers(start, end)
        val (active7, inPrivate) = repo.privateLeagueMembership(end.minusDays(6), end)
        val from = start.atStartOfDay(ZoneOffset.UTC).toInstant(); val until = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val invited = repo.invitedNewPlayers(from, until)
        return MetricsReport(
            from = start, to = end, cohorts = cohorts,
            d1 = retention(1) { it.d1 }, d7 = retention(7) { it.d7 },
            dau = repo.activeUsers(end, end), wau = repo.activeUsers(end.minusDays(6), end), mau = repo.activeUsers(end.minusDays(29), end),
            activeInPeriod = activeInPeriod,
            activeLast7 = active7, inPrivateLeague = inPrivate, privateLeagueShare = Analytics.rate(inPrivate, active7),
            invitedNewPlayers = invited, kFactor = Analytics.rate(invited, activeInPeriod),
            purchases = repo.purchases(from, until), adRewards = repo.adRewards(from, until), events = repo.eventCounts(from, until),
        )
    }
}

/** Logs club creation (the first step of every funnel) with the club's timezone bucket. */
class AnalyticsClubInitializer(private val events: EventLog) : ClubInitializer {
    override suspend fun onClubCreated(club: Club) {
        events.track(club.userId, "club_created", mapOf("timezone" to club.timezone))
    }
}
