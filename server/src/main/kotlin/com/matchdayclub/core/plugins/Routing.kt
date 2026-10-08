package com.matchdayclub.core.plugins

import com.matchdayclub.common.extension.dbQuery
import com.matchdayclub.common.response.toSuccessResponse
import com.matchdayclub.presentation.route.*
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
        route("/api/v1") {
            rateLimit(AUTH_RATE_LIMIT) { authRoutes() }
            rateLimit(API_RATE_LIMIT) { apiRoutes() }
        }
    }
}

fun Route.authRoutes() {
    authLoginRoutes()
}

fun Route.apiRoutes() {
    authSessionRoutes()
    meRoutes()
}
