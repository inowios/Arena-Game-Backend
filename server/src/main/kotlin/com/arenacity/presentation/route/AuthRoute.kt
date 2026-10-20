package com.arenacity.presentation.route

import com.arenacity.common.response.toSuccessResponse
import com.arenacity.core.plugins.AUTH_JWT
import com.arenacity.domain.exception.AuthorizationException
import com.arenacity.domain.usecase.auth.*
import com.arenacity.presentation.dto.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import java.util.UUID

/** Extracts the authenticated user id or throws. */
fun RoutingCall.userId(): UUID = principal<JWTPrincipal>()?.subject?.let { UUID.fromString(it) } ?: throw AuthorizationException("Not authenticated")

fun Route.authLoginRoutes() {
    val guestLogin: GuestLoginUseCase by inject()
    val transfer: com.arenacity.domain.usecase.account.TransferUseCases by inject()
    route("/auth") {
        /** Redeem a transfer code from the old device: this device now signs in to that account. */
        post("/transfer") {
            val r = call.receive<TransferRequest>()
            val res = transfer.redeem(r.code, r.deviceId)
            call.respond(AuthResponse(res.accessToken, res.refreshToken, res.user.toDto(), false).toSuccessResponse())
        }
        authenticate(AUTH_JWT) {
            post("/transfer-code") {
                val c = transfer.createCode(call.userId())
                call.respond(TransferCodeResponse(com.arenacity.domain.usecase.account.TransferUseCases.display(c.code), c.expiresAt.toString()).toSuccessResponse())
            }
        }
        post("/guest") {
            val r = guestLogin(call.receive<GuestLoginRequest>().deviceId)
            call.respond(AuthResponse(r.accessToken, r.refreshToken, r.user.toDto(), r.isNewUser).toSuccessResponse())
        }
    }
}

fun Route.authSessionRoutes() {
    val refresh: RefreshTokenUseCase by inject()
    route("/auth") {
        post("/refresh") {
            val t = refresh(call.receive<RefreshRequest>().refreshToken)
            call.respond(TokenResponse(t.accessToken, t.refreshToken).toSuccessResponse())
        }
    }
}
