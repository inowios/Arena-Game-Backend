package com.arenacity.presentation.route

import com.arenacity.common.response.toSuccessResponse
import com.arenacity.core.plugins.AUTH_JWT
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.usecase.arena.ArenaChestUseCase
import com.arenacity.domain.usecase.arena.ArenaMatchUseCases
import com.arenacity.domain.usecase.arena.ArenaReport
import com.arenacity.presentation.dto.ArenaResultRequest
import com.arenacity.presentation.dto.ArenaStartRequest
import com.arenacity.presentation.dto.toDto
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import java.util.UUID

/**
 * The Arena (the v4 match played on the device): a match starts for energy and hands out its token, its result (that
 * token, once) pays coins, trophies, hero experience and story progress; the weekly arena chest by the club's trophies.
 * /club/cards/start and /result are the paths of the card era, kept as aliases for one client version (20 October 2026).
 */
fun Route.arenaRoutes() {
    val arena: ArenaMatchUseCases by inject()
    val chest: ArenaChestUseCase by inject()

    authenticate(AUTH_JWT) {
        for (base in listOf("/club/arena", "/club/cards")) route(base) {
            // a match starts: it takes its energy (400 «Not enough energy» when short); the body is optional
            post("/start") {
                val r = runCatching { call.receive<ArenaStartRequest>() }.getOrDefault(ArenaStartRequest())
                call.respond(HttpStatusCode.Created, arena.start(call.userId(), r.stake, r.story).toDto().toSuccessResponse(201))
            }
            post("/result") {
                val r = call.receive<ArenaResultRequest>()
                val id = r.matchId?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw NotFoundException("Match not found")
                call.respond(arena.report(call.userId(), ArenaReport(id, r.won, r.draw, r.conceded, r.heroes, r.knockouts, r.altSkills, r.seed)).toDto().toSuccessResponse())
            }
        }
        route("/live") {
            get("/chest") { call.respond(chest.view(call.userId()).toDto().toSuccessResponse()) }
            post("/chest/claim") { val (c, club) = chest.claim(call.userId()); call.respond(c.toDto(club).toSuccessResponse()) }
        }
    }
}
