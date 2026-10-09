package com.matchdayclub.presentation.route

import com.matchdayclub.common.response.toSuccessResponse
import com.matchdayclub.core.plugins.AUTH_JWT
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.repository.ClubRepository
import com.matchdayclub.domain.usecase.club.ClubService
import com.matchdayclub.domain.usecase.social.*
import com.matchdayclub.presentation.dto.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import java.time.Instant
import java.util.UUID

private fun RoutingCall.id(name: String) = runCatching { UUID.fromString(parameters[name]) }.getOrElse { throw ValidationException("Bad $name") }
private fun RoutingCall.instantParam(name: String) = request.queryParameters[name]?.let { runCatching { Instant.parse(it) }.getOrElse { throw ValidationException("Bad $name") } }

/** Public links config the client needs to build invite links (no auth). */
fun Route.configRoutes() {
    get("/config") {
        val c = call.application.environment.config
        val base = c.propertyOrNull("links.baseUrl")?.getString()?.trimEnd('/') ?: ""
        val scheme = c.propertyOrNull("links.scheme")?.getString() ?: "matchdayclub"
        call.respond(ClientConfigDto(base, scheme, c.propertyOrNull("client.minVersion")?.getString() ?: "0.0.0",
            c.propertyOrNull("links.playStoreUrl")?.getString() ?: "", c.propertyOrNull("links.appStoreUrl")?.getString() ?: "",
            "$base/l/{code}", "$base/c/{code}").toSuccessResponse())
    }
}

fun Route.socialRoutes() {
    val chat: ChatService by inject()
    val admin: PrivateLeagueAdmin by inject()
    val h2h: HeadToHeadUseCase by inject()
    val grounds: GetGroundsUseCase by inject()
    val challenges: ChallengeUseCases by inject()
    val clubs: ClubRepository by inject()
    val clubService: ClubService by inject()

    authenticate(AUTH_JWT) {
        route("/leagues/{id}/messages") {
            get {
                val lines = chat.list(call.userId(), call.id("id"), call.instantParam("before"), call.instantParam("after"), call.request.queryParameters["limit"]?.toIntOrNull() ?: 50)
                call.respond(lines.map { it.toDto() }.toSuccessResponse())
            }
            post { call.respond(HttpStatusCode.Created, chat.post(call.userId(), call.id("id"), call.receive<PostMessageRequest>().text).toDto().toSuccessResponse(201)) }
        }
        post("/messages/{id}/report") {
            val reason = runCatching { call.receive<ReportRequest>() }.getOrDefault(ReportRequest()).reason
            chat.report(call.userId(), call.id("id"), reason); call.respond(mapOf("ok" to true).toSuccessResponse())
        }
        delete("/messages/{id}") { chat.delete(call.userId(), call.id("id")); call.respond(mapOf("ok" to true).toSuccessResponse()) }
        post("/clubs/{id}/block") { chat.block(call.userId(), call.id("id")); call.respond(mapOf("ok" to true).toSuccessResponse()) }
        delete("/clubs/{id}/block") { chat.unblock(call.userId(), call.id("id")); call.respond(mapOf("ok" to true).toSuccessResponse()) }
        get("/club/blocks") { call.respond(chat.blocked(call.userId()).map { it.toString() }.toSuccessResponse()) }

        route("/leagues/private/{id}") {
            post("/restart") { call.respond(admin.restart(call.userId(), call.id("id")).toDto(0).toSuccessResponse()) }
            delete("/members/{clubId}") { admin.kick(call.userId(), call.id("id"), call.id("clubId")); call.respond(mapOf("ok" to true).toSuccessResponse()) }
            get("/history") { call.respond(admin.history(call.userId(), call.id("id")).map { it.toDto() }.toSuccessResponse()) }
        }

        get("/clubs/{id}/h2h") {
            val r = h2h(call.userId(), call.id("id"))
            val ids = r.fixtures.flatMap { listOf(it.homeClubId, it.awayClubId) }.toSet()
            call.respond(r.toDto(clubs.findByIds(ids).associateBy { it.id }).toSuccessResponse())
        }
        get("/clubs/{id}/grounds") { call.respond(grounds(call.id("id")).toDto().toSuccessResponse()) }

        route("/challenges") {
            val cfg = application.environment.config
            val base = cfg.propertyOrNull("links.baseUrl")?.getString()?.trimEnd('/') ?: ""
            val scheme = cfg.propertyOrNull("links.scheme")?.getString() ?: "matchdayclub"
            post {
                val c = challenges.create(call.userId())
                call.respond(HttpStatusCode.Created, c.toDto(clubService.clubOf(call.userId()), base, scheme).toSuccessResponse(201))
            }
            get("/mine") {
                val me = clubService.clubOf(call.userId())
                call.respond(challenges.mine(call.userId()).map { it.toDto(me, base, scheme) }.toSuccessResponse())
            }
            get("/{code}") { call.respond(challenges.view(call.parameters["code"]!!).toDto(base, scheme).toSuccessResponse()) }
            post("/{code}/accept") { call.respond(HttpStatusCode.Created, challenges.accept(call.userId(), call.parameters["code"]!!).toDto().toSuccessResponse(201)) }
        }
    }
}
