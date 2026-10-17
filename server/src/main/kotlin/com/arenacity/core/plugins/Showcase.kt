package com.arenacity.core.plugins

import com.arenacity.domain.usecase.social.ShowcaseService
import io.ktor.server.application.*
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

/** Creates (or refreshes) the two showcase friends (their open spots and islands) before the server takes requests. */
fun Application.configureShowcase() {
    val showcase: ShowcaseService by inject()
    val log = LoggerFactory.getLogger("Showcase")
    runCatching { runBlocking { showcase.ensure() } }
        .onSuccess { log.info("showcase friends ready") }
        .onFailure { log.error("showcase friends failed", it) }
}
