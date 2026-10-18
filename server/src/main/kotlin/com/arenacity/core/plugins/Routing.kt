package com.arenacity.core.plugins

import com.arenacity.common.extension.dbQuery
import com.arenacity.common.response.toSuccessResponse
import com.arenacity.presentation.route.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.transactions.TransactionManager

fun Application.configureRouting() {
    routing {
        get("/health") {
            val ok = runCatching { dbQuery { TransactionManager.current().exec("SELECT 1") }; true }.getOrDefault(false)
            if (ok) call.respond("ok".toSuccessResponse()) else call.respond(HttpStatusCode.ServiceUnavailable, "db down".toSuccessResponse(503))
        }
        linkRoutes()
        legalRoutes()
        val adminToken = environment.config.propertyOrNull("admin.token")?.getString()?.trim().orEmpty()
        if (adminToken.length >= ADMIN_TOKEN_MIN) rateLimit(AUTH_RATE_LIMIT) { adminRoutes(adminToken) }
        else if (adminToken.isNotEmpty()) environment.log.warn("ADMIN_TOKEN shorter than $ADMIN_TOKEN_MIN characters: admin endpoints and /metrics are disabled")
        route("/api/v1") {
            configRoutes()
            rateLimit(AUTH_RATE_LIMIT) { authRoutes() }
            rateLimit(API_RATE_LIMIT) { apiRoutes() }
            if (developmentMode) devRoutes()
        }
    }
}

fun Route.authRoutes() {
    authLoginRoutes()
}

fun Route.apiRoutes() {
    authSessionRoutes()
    meRoutes()
    clubRoutes()
    athleteRoutes()
    heroRoutes()
    storyRoutes()
    leagueRoutes()
    economyRoutes()
    storeNotificationRoutes()
    socialRoutes()
    allianceRoutes()
    liveOpsRoutes()
    analyticsRoutes()
    cupRoutes()
    liveRoutes()
    marketRoutes()
}
