package com.matchdayclub.presentation.route

import com.matchdayclub.common.response.toSuccessResponse
import com.matchdayclub.core.plugins.AUTH_JWT
import com.matchdayclub.domain.exception.AuthorizationException
import com.matchdayclub.domain.model.AuthProvider
import com.matchdayclub.domain.usecase.auth.*
import com.matchdayclub.presentation.dto.*
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
    val oauthLogin: OAuthLoginUseCase by inject()
    val transfer: com.matchdayclub.domain.usecase.account.TransferUseCases by inject()
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
                call.respond(TransferCodeResponse(com.matchdayclub.domain.usecase.account.TransferUseCases.display(c.code), c.expiresAt.toString()).toSuccessResponse())
            }
        }
        post("/guest") {
            val r = guestLogin(call.receive<GuestLoginRequest>().deviceId)
            call.respond(AuthResponse(r.accessToken, r.refreshToken, r.user.toDto(), r.isNewUser).toSuccessResponse())
        }
        authenticate(AUTH_JWT, optional = true) {
            for ((path, provider) in listOf("google" to AuthProvider.GOOGLE, "apple" to AuthProvider.APPLE)) {
                post("/oauth/$path") {
                    val current = call.principal<JWTPrincipal>()?.subject?.let { UUID.fromString(it) }
                    val r = oauthLogin(provider, call.receive<OAuthLoginRequest>().idToken, current)
                    call.respond(AuthResponse(r.accessToken, r.refreshToken, r.user.toDto(), r.isNewUser).toSuccessResponse())
                }
            }
        }
    }
}

fun Route.authSessionRoutes() {
    val refresh: RefreshTokenUseCase by inject()
    val logout: LogoutUseCase by inject()
    route("/auth") {
        post("/refresh") {
            val t = refresh(call.receive<RefreshRequest>().refreshToken)
            call.respond(TokenResponse(t.accessToken, t.refreshToken).toSuccessResponse())
        }
        post("/logout") { logout.logout(call.receive<RefreshRequest>().refreshToken); call.respond(mapOf("ok" to true).toSuccessResponse()) }
        authenticate(AUTH_JWT) {
            post("/logout-all") { logout.logoutAll(call.userId()); call.respond(mapOf("ok" to true).toSuccessResponse()) }
        }
    }
}
