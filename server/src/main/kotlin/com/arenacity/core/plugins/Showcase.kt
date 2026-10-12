package com.arenacity.core.plugins

import com.arenacity.domain.usecase.social.ShowcaseService
import io.ktor.server.application.*
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

/** Builds (or refreshes) the two showcase friend cities before the server takes requests. */
fun Application.configureShowcase() {
    val showcase: ShowcaseService by inject()
    val log = LoggerFactory.getLogger("Showcase")
    runCatching { runBlocking { showcase.ensure() } }
        .onSuccess { log.info("showcase cities ready") }
        .onFailure { log.error("showcase cities failed", it) }
}
