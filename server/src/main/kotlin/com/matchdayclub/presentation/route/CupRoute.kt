package com.matchdayclub.presentation.route

import com.matchdayclub.common.response.toSuccessResponse
import com.matchdayclub.core.plugins.AUTH_JWT
import com.matchdayclub.domain.usecase.cup.CupQueries
import com.matchdayclub.presentation.dto.toDto
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/** Weekly cup: this week's bracket (or the next draw time and last week's result). */
fun Route.cupRoutes() {
    val queries: CupQueries by inject()
    authenticate(AUTH_JWT) {
        get("/cups/current") { call.respond(queries.current(call.userId()).toDto().toSuccessResponse()) }
    }
}
