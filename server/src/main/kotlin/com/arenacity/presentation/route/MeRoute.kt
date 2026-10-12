package com.arenacity.presentation.route

import com.arenacity.common.response.toSuccessResponse
import com.arenacity.core.plugins.AUTH_JWT
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.repository.UserRepository
import com.arenacity.presentation.dto.MeResponse
import com.arenacity.presentation.dto.UpdateMeRequest
import com.arenacity.presentation.dto.toDto
import io.ktor.server.request.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import java.util.UUID

/** Replaced by the club repository binding once clubs exist. */
fun interface ClubExistence { suspend fun hasClub(userId: UUID): Boolean }

fun Route.meRoutes() {
    val users: UserRepository by inject()
    val clubs: ClubExistence by inject()
    val deleteAccount: com.arenacity.domain.usecase.account.DeleteAccountUseCase by inject()
    authenticate(AUTH_JWT) {
        get("/me") {
            val id = call.userId()
            val user = users.findById(id) ?: throw NotFoundException("User not found")
            if (!user.isActive) throw com.arenacity.domain.exception.AuthorizationException("Account deactivated")
            call.respond(MeResponse(user.toDto(), clubs.hasClub(id)).toSuccessResponse())
        }
        /** In-app account deletion: sign-in data erased, the club retires into a bot. */
        delete("/me") {
            deleteAccount(call.userId())
            call.respond(mapOf("ok" to true).toSuccessResponse())
        }
        /** The app reports its language (pushes are sent in it). */
        patch("/me") {
            val id = call.userId()
            val r = call.receive<UpdateMeRequest>()
            val user = users.findById(id) ?: throw NotFoundException("User not found")
            val locale = r.locale?.let { com.arenacity.common.i18n.ServerI18n.normalize(it) }
            val updated = if (locale != null && locale != user.locale) users.update(user.copy(locale = locale, updatedAt = java.time.Instant.now())) else user
            call.respond(MeResponse(updated.toDto(), clubs.hasClub(id)).toSuccessResponse())
        }
    }
}
