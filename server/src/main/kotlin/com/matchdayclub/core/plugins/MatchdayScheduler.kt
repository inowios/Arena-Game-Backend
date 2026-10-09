package com.matchdayclub.core.plugins

import com.matchdayclub.core.metrics.Metrics
import com.matchdayclub.domain.usecase.league.MatchdayRunner
import io.ktor.server.application.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

private const val TICK_MS = 5L * 60 * 1000

/** What the last scheduler pass did (ops overview and metrics). */
object SchedulerStatus {
    @Volatile var lastTickAt: Instant? = null
    @Volatile var lastMatches = 0
    @Volatile var lastCupMatches = 0
    @Volatile var lastError: String? = null
    val ticks = AtomicLong()
    val failures = AtomicLong()
}

/** One pass: due league rounds (and their season ends). Shared by the timer loop and POST /admin/scheduler/run. */
class SchedulerTick(private val runner: MatchdayRunner) {
    private val mutex = Mutex()
    private val log = LoggerFactory.getLogger("MatchdayScheduler")

    suspend fun run(): Pair<Int, Int> = mutex.withLock {
        SchedulerStatus.ticks.incrementAndGet(); Metrics.inc("scheduler_ticks_total")
        var matches = 0
        try {
            matches = runner.runDueRounds()
            SchedulerStatus.lastError = null
        } catch (e: Exception) {
            SchedulerStatus.failures.incrementAndGet(); Metrics.inc("scheduler_failures_total"); SchedulerStatus.lastError = e.message ?: e.javaClass.simpleName
            log.error("Matchday tick failed", e)
        }
        if (matches > 0) { Metrics.inc("matches_played_total", "kind" to "league", by = matches.toLong()); log.info("Matchday tick: played $matches matches") }
        SchedulerStatus.lastMatches = matches; SchedulerStatus.lastTickAt = Instant.now()
        matches to 0
    }
}

/** Plays due league rounds every 5 minutes (and shortly after startup). Single instance assumed. */
fun Application.configureMatchdayScheduler() {
    val tick: SchedulerTick by inject()
    val log = LoggerFactory.getLogger("MatchdayScheduler")
    Metrics.counter("scheduler_ticks_total", "Scheduler passes")
    Metrics.counter("scheduler_failures_total", "Scheduler passes that threw")
    Metrics.counter("matches_played_total", "Matches simulated by the scheduler")
    Metrics.gauge("scheduler_last_tick_timestamp_seconds", "Unix time of the last scheduler pass (alert when stale)") { SchedulerStatus.lastTickAt?.epochSecond?.toDouble() ?: 0.0 }
    val enabled = environment.config.propertyOrNull("matchday.schedulerEnabled")?.getString()?.toBoolean() ?: true
    if (!enabled) { log.info("Matchday scheduler disabled"); return }
    launch {
        delay(10_000)
        while (true) {
            tick.run()
            delay(TICK_MS)
        }
    }
}
