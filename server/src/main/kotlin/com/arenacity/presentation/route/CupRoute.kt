package com.arenacity.presentation.route

import com.arenacity.common.response.toSuccessResponse
import com.arenacity.core.plugins.AUTH_JWT
import com.arenacity.domain.usecase.cup.CupQueries
import com.arenacity.presentation.dto.toDto
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
