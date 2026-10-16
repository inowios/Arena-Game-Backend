package com.arenacity.presentation.route

import com.arenacity.common.response.toSuccessResponse
import com.arenacity.core.plugins.AUTH_JWT
import com.arenacity.domain.usecase.hero.HeroUseCases
import com.arenacity.presentation.dto.toDto
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Heroes instead of cards (docs/claude/HEROES-INSTEAD-OF-CARDS.md in the mobile repo): the roster and opening a hero for
 * gold. Match experience comes with the card match result (POST /club/cards/result, its «heroes»).
 */
fun Route.heroRoutes() {
    val heroes: HeroUseCases by inject()

    authenticate(AUTH_JWT) {
        route("/club/heroes") {
            get { call.respond(heroes.roster(call.userId()).toDto().toSuccessResponse()) }
            // idempotent: a hero the club already owns answers 200 with spent 0
            post("/{id}/unlock") { call.respond(heroes.unlock(call.userId(), call.parameters["id"].orEmpty()).toDto().toSuccessResponse()) }
        }
    }
}
