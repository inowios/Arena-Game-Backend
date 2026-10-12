package com.arenacity.presentation.route

import com.arenacity.common.response.toSuccessResponse
import com.arenacity.core.plugins.AUTH_JWT
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.usecase.alliance.AllianceUseCases
import com.arenacity.domain.usecase.social.ChatService
import com.arenacity.presentation.dto.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import java.time.Instant
import java.util.UUID

private fun RoutingCall.uuid(name: String) = runCatching { UUID.fromString(parameters[name]) }.getOrElse { throw ValidationException("Bad $name") }

/** Alliances: search, create, join, members and roles, chat, help on constructions, the weekend tournament, likes. */
fun Route.allianceRoutes() {
    val alliances: AllianceUseCases by inject()
    val chat: ChatService by inject()

    authenticate(AUTH_JWT) {
        get("/alliances") { call.respond(alliances.search(call.request.queryParameters["q"]).map { it.toDto() }.toSuccessResponse()) }
        post("/alliances") {
            val r = call.receive<CreateAllianceRequest>()
            call.respond(HttpStatusCode.Created, alliances.create(call.userId(), r.name, r.tag, r.description, r.open, r.minTrophies, r.emblemJson).toDto().toSuccessResponse(201))
        }
        get("/alliances/tournament") { call.respond(alliances.tournament(call.userId()).toDto().toSuccessResponse()) }
        get("/alliances/{id}") { call.respond(alliances.details(call.userId(), call.uuid("id")).toDto().toSuccessResponse()) }
        post("/alliances/{id}/join") {
            val r = runCatching { call.receive<JoinAllianceRequest>() }.getOrDefault(JoinAllianceRequest())
            val v = alliances.join(call.userId(), call.uuid("id"), r.message)
            call.respond(mapOf("joined" to (v != null)).toSuccessResponse())
        }
        route("/club/alliance") {
            get { val v = alliances.mine(call.userId()); call.respond((v?.toDto()).toSuccessResponse()) }
            patch { val r = call.receive<UpdateAllianceRequest>(); call.respond(alliances.update(call.userId(), r.description, r.open, r.minTrophies, r.emblemJson).toDto().toSuccessResponse()) }
            post("/leave") { alliances.leave(call.userId()); call.respond(mapOf("ok" to true).toSuccessResponse()) }
            post("/requests/{clubId}/{action}") {
                val accept = when (call.parameters["action"]) { "accept" -> true; "reject" -> false; else -> throw ValidationException("Unknown action") }
                call.respond(alliances.answer(call.userId(), call.uuid("clubId"), accept).toDto().toSuccessResponse())
            }
            post("/members/{clubId}/{action}") { call.respond(alliances.manage(call.userId(), call.uuid("clubId"), call.parameters["action"] ?: "").toDto().toSuccessResponse()) }
            post("/help") {
                val r = call.receive<AskHelpRequest>()
                val id = runCatching { UUID.fromString(r.buildingId) }.getOrElse { throw ValidationException("Bad building id") }
                alliances.askHelp(call.userId(), id); call.respond(HttpStatusCode.Created, mapOf("ok" to true).toSuccessResponse(201))
            }
            post("/help/{id}") { val h = alliances.giveHelp(call.userId(), call.uuid("id")); call.respond(HelpResultDto(h.coins, h.secondsCut, h.club.toDto()).toSuccessResponse()) }
            post("/tournament/claim") { call.respond(alliances.claimWeekend(call.userId()).toDto().toSuccessResponse()) }
            route("/messages") {
                get {
                    val v = alliances.mine(call.userId()) ?: throw com.arenacity.domain.exception.NotFoundException("You are not in an alliance")
                    val q = call.request.queryParameters
                    val lines = chat.list(call.userId(), v.alliance.id, q["before"]?.let(Instant::parse), q["after"]?.let(Instant::parse), q["limit"]?.toIntOrNull() ?: 50)
                    call.respond(lines.map { it.toDto() }.toSuccessResponse())
                }
                post {
                    val v = alliances.mine(call.userId()) ?: throw com.arenacity.domain.exception.NotFoundException("You are not in an alliance")
                    call.respond(HttpStatusCode.Created, chat.post(call.userId(), v.alliance.id, call.receive<PostMessageRequest>().text).toDto().toSuccessResponse(201))
                }
            }
        }
        post("/clubs/{id}/like") { call.respond(mapOf("likes" to alliances.like(call.userId(), call.uuid("id"))).toSuccessResponse()) }
        get("/clubs/{id}/likes") { val s = alliances.likeState(call.userId(), call.uuid("id")); call.respond(LikeStateDto(s.likes, s.likedByMe, s.alliance?.toDto(0)).toSuccessResponse()) }
        get("/clubs/top-liked") { call.respond(alliances.topLiked().map { (c, n) -> TopLikedDto(c.toRef(), n) }.toSuccessResponse()) }
    }
}
