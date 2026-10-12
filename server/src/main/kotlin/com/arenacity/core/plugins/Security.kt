package com.arenacity.core.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import org.koin.ktor.ext.inject
import java.util.UUID

const val AUTH_JWT = "auth-jwt"

fun Application.configureSecurity() {
    val cfg = environment.config
    val audience = cfg.property("jwt.audience").getString()
    val issuer = cfg.property("jwt.issuer").getString()
    val secret = cfg.property("jwt.secret").getString()
    val activity: com.arenacity.domain.usecase.analytics.ActivityTracker by inject()
    install(Authentication) {
        jwt(AUTH_JWT) {
            realm = cfg.property("jwt.realm").getString()
            verifier(JWT.require(Algorithm.HMAC256(secret)).withAudience(audience).withIssuer(issuer).build())
            validate { credential ->
                if (!credential.payload.audience.contains(audience)) return@validate null
                // one row per user and UTC day (retention, DAU, cup entry); cached in memory, never fails the request
                credential.payload.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { activity.seen(it) }
                JWTPrincipal(credential.payload)
            }
        }
    }
}
