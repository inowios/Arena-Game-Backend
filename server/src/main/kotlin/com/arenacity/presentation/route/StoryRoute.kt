package com.arenacity.presentation.route

import com.arenacity.common.response.toSuccessResponse
import com.arenacity.core.plugins.AUTH_JWT
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.usecase.story.StoryUseCases
import com.arenacity.presentation.dto.toDto
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * The story «Іскра» (docs/claude/HEROES-INSTEAD-OF-CARDS.md §12 in the mobile repo): the season's chapters with the
 * club's progress, and claiming a finished chapter. Progress comes with the card match result (POST /club/cards/result,
 * its «story» = the chapter of a story match).
 */
fun Route.storyRoutes() {
    val story: StoryUseCases by inject()

    authenticate(AUTH_JWT) {
        route("/club/story") {
            get { call.respond(story.story(call.userId()).toDto().toSuccessResponse()) }
            post("/{chapter}/claim") {
                val n = call.parameters["chapter"]?.toIntOrNull() ?: throw NotFoundException("No such chapter")
                call.respond(story.claim(call.userId(), n).toDto().toSuccessResponse())
            }
        }
    }
}
