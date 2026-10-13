package com.arenacity.core.plugins

import com.arenacity.domain.rules.LandMap
import com.arenacity.domain.usecase.club.LandSweep
import io.ktor.server.application.*
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory

/**
 * Loads the land mask (a broken one stops the server here, before it takes requests) and moves every club onto it
 * that is not on it yet (rules.Lands, ClubService.settle).
 */
fun Application.configureLand() {
    val lands: LandMap by inject()
    val sweep: LandSweep by inject()
    val log = LoggerFactory.getLogger("Land")
    if (lands.computedId != lands.id) log.warn("land mask ${lands.id}: its regions hash to ${lands.computedId} (check the art pipeline's id)")
    log.info("land mask ${lands.id}: ${lands.regions.joinToString { "${it.id} ${it.area} cells" + (it.lock?.let { l -> " locked by $l" } ?: "") }}")
    val settled = runBlocking { sweep.run { id, e -> log.error("land: club $id not settled", e) } }
    log.info("land: $settled club(s) settled on mask ${lands.id}")
}
