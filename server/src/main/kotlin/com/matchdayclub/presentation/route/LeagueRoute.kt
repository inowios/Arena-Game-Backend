package com.matchdayclub.presentation.route

import com.matchdayclub.common.response.toSuccessResponse
import com.matchdayclub.core.plugins.AUTH_JWT
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.usecase.club.ClubService
import com.matchdayclub.domain.usecase.league.*
import com.matchdayclub.presentation.dto.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import java.util.UUID

private fun RoutingCall.uuid(name: String) = runCatching { UUID.fromString(parameters[name]) }.getOrElse { throw ValidationException("Bad $name") }

fun Route.leagueRoutes() {
    val queries: LeagueQueries by inject()
    val clubService: ClubService by inject()
    val createPrivate: CreatePrivateLeagueUseCase by inject()
    val joinPrivate: JoinPrivateLeagueUseCase by inject()
    val leavePrivate: LeavePrivateLeagueUseCase by inject()
    val friendly: PlayFriendlyUseCase by inject()
    val publicClub: GetPublicClubUseCase by inject()
    val leagues: com.matchdayclub.domain.repository.LeagueRepository by inject()
    val history: GetClubHistoryUseCase by inject()

    authenticate(AUTH_JWT) {
        route("/leagues") {
            get("/mine") {
                val club = clubService.clubOf(call.userId())
                val (pub, priv) = queries.mine(club.id)
                call.respond(MyLeaguesResponse(pub?.toDto(), priv.map { it.toDto() }).toSuccessResponse())
            }
            get("/{id}") { call.respond(queries.view(call.uuid("id")).toDto().toSuccessResponse()) }
            get("/{id}/fixtures") {
                val v = queries.fixtures(call.uuid("id"), call.request.queryParameters["round"]?.toIntOrNull())
                call.respond(v.fixtures.map { it.toDto(v.clubs) }.toSuccessResponse())
            }
            post("/private") {
                val l = createPrivate(call.userId(), call.receive<CreatePrivateLeagueRequest>().name)
                call.respond(HttpStatusCode.Created, l.toDto(1).toSuccessResponse(201))
            }
            post("/private/join") {
                val l = joinPrivate(call.userId(), call.receive<JoinPrivateLeagueRequest>().code)
                call.respond(l.toDto(leagues.members(l.id).size).toSuccessResponse())
            }
            delete("/private/{id}/leave") { leavePrivate(call.userId(), call.uuid("id")); call.respond(mapOf("ok" to true).toSuccessResponse()) }
        }
        get("/matches/{id}") { call.respond(queries.match(call.uuid("id")).toDto().toSuccessResponse()) }
        get("/club/fixtures") {
            val club = clubService.clubOf(call.userId())
            val (next, recent) = queries.clubFixtures(club.id)
            call.respond(ClubFixturesResponse(next?.toDto(recent.clubs), recent.fixtures.map { it.toDto(recent.clubs) }).toSuccessResponse())
        }
        post("/friendlies") {
            val r = runCatching { call.receive<PlayFriendlyRequest>() }.getOrDefault(PlayFriendlyRequest())
            val opp = r.opponentClubId?.let { runCatching { UUID.fromString(it) }.getOrElse { throw ValidationException("Bad opponentClubId") } }
            call.respond(HttpStatusCode.Created, friendly(call.userId(), opp).toDto().toSuccessResponse(201))
        }
        get("/clubs/{id}") { call.respond(publicClub(call.uuid("id")).toDto().toSuccessResponse()) }
        get("/clubs/{id}/history") { call.respond(history(call.uuid("id")).toDto().toSuccessResponse()) }
        get("/club/history") { call.respond(history(clubService.clubOf(call.userId()).id).toDto().toSuccessResponse()) }
    }
}

/** Development-only helpers (enabled by ktor.development=true). */
fun Route.devRoutes() {
    val runner: MatchdayRunner by inject()
    route("/dev") {
        post("/matchday/run") { call.respond(mapOf("played" to runner.runDueRounds()).toSuccessResponse()) }
    }
}
