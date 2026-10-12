package com.arenacity.presentation.route

import com.arenacity.common.response.toSuccessResponse
import com.arenacity.common.time.Clock
import com.arenacity.core.plugins.AUTH_JWT
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.usecase.live.FinishLiveMatchUseCase
import com.arenacity.domain.usecase.live.LiveReport
import com.arenacity.domain.usecase.live.StartLiveMatchUseCase
import com.arenacity.presentation.dto.LiveStartRequest
import com.arenacity.presentation.dto.toDto
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
    val cancel: com.arenacity.domain.usecase.live.CancelLiveMatchUseCase by inject()
    val recent: com.arenacity.domain.usecase.live.RecentLiveMatchesUseCase by inject()
    val clock: Clock by inject()
    val chest: com.arenacity.domain.usecase.live.ArenaChestUseCase by inject()
    authenticate(AUTH_JWT) { route("/live") {
        get("/recent") { call.respond(recent(call.userId()).map { it.toDto() }.toSuccessResponse()) }
        get("/chest") { call.respond(chest.view(call.userId()).toDto().toSuccessResponse()) }
        post("/chest/claim") { val (c, club) = chest.claim(call.userId()); call.respond(c.toDto(club).toSuccessResponse()) }
        post("/start") {
            val r = runCatching { call.receive<LiveStartRequest>() }.getOrDefault(LiveStartRequest())
            val opp = r.opponentClubId?.let { runCatching { UUID.fromString(it) }.getOrElse { throw ValidationException("Bad opponentClubId") } }
            call.respond(HttpStatusCode.Created, start(call.userId(), opp).toDto(clock.now()).toSuccessResponse(201))
        }
        post("/{id}/cancel") { cancel(call.userId(), call.uuid("id")); call.respond(mapOf("ok" to true).toSuccessResponse()) }
        post("/{id}/finish") {
            val report = call.receive<LiveReport>()
            call.respond(finish(call.userId(), call.uuid("id"), report).toDto(clock.now()).toSuccessResponse())
        }
    } }
}
