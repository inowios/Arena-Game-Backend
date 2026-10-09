package com.matchdayclub.presentation.route

import com.matchdayclub.common.response.toSuccessResponse
import com.matchdayclub.common.time.Clock
import com.matchdayclub.core.plugins.AUTH_JWT
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.usecase.live.FinishLiveMatchUseCase
import com.matchdayclub.domain.usecase.live.LiveReport
import com.matchdayclub.domain.usecase.live.StartLiveMatchUseCase
import com.matchdayclub.presentation.dto.LiveStartRequest
import com.matchdayclub.presentation.dto.toDto
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import java.util.UUID

private fun RoutingCall.uuid(name: String) = runCatching { UUID.fromString(parameters[name]) }.getOrElse { throw ValidationException("Bad $name") }

/** Live matches played on the device: /live/start hands out the line-ups, /live/{id}/finish takes the result. */
fun Route.liveRoutes() {
    val start: StartLiveMatchUseCase by inject()
    val finish: FinishLiveMatchUseCase by inject()
    val clock: Clock by inject()
    authenticate(AUTH_JWT) { route("/live") {
        post("/start") {
            val r = runCatching { call.receive<LiveStartRequest>() }.getOrDefault(LiveStartRequest())
            val opp = r.opponentClubId?.let { runCatching { UUID.fromString(it) }.getOrElse { throw ValidationException("Bad opponentClubId") } }
            call.respond(HttpStatusCode.Created, start(call.userId(), opp).toDto(clock.now()).toSuccessResponse(201))
        }
        post("/{id}/finish") {
            val report = call.receive<LiveReport>()
            call.respond(finish(call.userId(), call.uuid("id"), report).toDto(clock.now()).toSuccessResponse())
        }
    } }
}
