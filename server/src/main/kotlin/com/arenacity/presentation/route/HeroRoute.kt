package com.arenacity.presentation.route

import com.arenacity.common.response.toSuccessResponse
import com.arenacity.core.plugins.AUTH_JWT
import com.arenacity.domain.usecase.hero.HeroUseCases
import com.arenacity.presentation.dto.HeroLineupRequest
import com.arenacity.presentation.dto.toDto
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Heroes instead of cards (docs/claude/HEROES-INSTEAD-OF-CARDS.md in the mobile repo): the roster, opening a hero for
 * gold, the «Склад» lineup of the card match and training in the training centre. Match experience comes with the card
 * match result (POST /club/cards/result, its «heroes»).
 */
fun Route.heroRoutes() {
    val heroes: HeroUseCases by inject()

    authenticate(AUTH_JWT) {
        route("/club/heroes") {
            get { call.respond(heroes.roster(call.userId()).toDto().toSuccessResponse()) }
            // idempotent: a hero the club already owns answers 200 with spent 0
            post("/{id}/unlock") { call.respond(heroes.unlock(call.userId(), call.parameters["id"].orEmpty()).toDto().toSuccessResponse()) }
            // the «Склад» lineup: no saved one = saved false and empty lists (the match picks the best heroes)
            get("/lineup") { call.respond(heroes.lineup(call.userId()).toDto().toSuccessResponse()) }
            put("/lineup") {
                val r = call.receive<HeroLineupRequest>()
                call.respond(heroes.saveLineup(call.userId(), r.field, r.keeper, r.bench).toDto().toSuccessResponse())
            }
            // training: the centre's offer and the heroes in a session; start one for coins; claim it after the timer
            get("/training") { call.respond(heroes.trainingStatus(call.userId()).toDto().toSuccessResponse()) }
            post("/{id}/train") { call.respond(HttpStatusCode.Created, heroes.train(call.userId(), call.parameters["id"].orEmpty()).toDto().toSuccessResponse(201)) }
            post("/{id}/train/claim") { call.respond(heroes.claimTraining(call.userId(), call.parameters["id"].orEmpty()).toDto().toSuccessResponse()) }
        }
    }
}
