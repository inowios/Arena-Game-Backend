package com.arenacity.presentation.route

import com.arenacity.common.response.toSuccessResponse
import com.arenacity.core.plugins.AUTH_JWT
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.usecase.liveops.LiveOps
import com.arenacity.presentation.dto.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import java.util.UUID

/** Live game for players: events with goals and developer mail. */
fun Route.liveOpsRoutes() {
    val ops: LiveOps by inject()
    authenticate(AUTH_JWT) {
        get("/events") { call.respond(ops.list(call.userId()).map { it.toDto() }.toSuccessResponse()) }
        post("/events/{id}/claim") { call.respond(ops.claim(call.userId(), call.parameters["id"] ?: "").toDto().toSuccessResponse()) }
        get("/club/mail") { call.respond(ops.mail(call.userId()).map { it.toDto() }.toSuccessResponse()) }
        post("/club/mail/{id}/claim") {
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrElse { throw ValidationException("Mail not found") }
            call.respond(ops.claimMail(call.userId(), id).toDto().toSuccessResponse())
        }
    }
}
