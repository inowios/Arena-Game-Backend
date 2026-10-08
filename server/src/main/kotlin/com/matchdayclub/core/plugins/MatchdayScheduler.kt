package com.matchdayclub.core.plugins

import com.matchdayclub.domain.usecase.league.MatchdayRunner
import io.ktor.server.application.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

private const val TICK_MS = 5L * 60 * 1000

/** Plays due league rounds every 5 minutes (and shortly after startup). Single instance assumed. */
fun Application.configureMatchdayScheduler() {
    val runner: MatchdayRunner by inject()
    val log = LoggerFactory.getLogger("MatchdayScheduler")
    val enabled = environment.config.propertyOrNull("matchday.schedulerEnabled")?.getString()?.toBoolean() ?: true
    if (!enabled) { log.info("Matchday scheduler disabled"); return }
    launch {
        delay(10_000)
        while (true) {
            try {
                val n = runner.runDueRounds()
                if (n > 0) log.info("Matchday tick: played $n matches")
            } catch (e: Exception) {
                log.error("Matchday tick failed", e)
            }
            delay(TICK_MS)
        }
    }
}
