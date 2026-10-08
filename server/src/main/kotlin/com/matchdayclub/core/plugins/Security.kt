package com.matchdayclub.core.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

const val AUTH_JWT = "auth-jwt"

fun Application.configureSecurity() {
    val cfg = environment.config
    val audience = cfg.property("jwt.audience").getString()
    val issuer = cfg.property("jwt.issuer").getString()
    val secret = cfg.property("jwt.secret").getString()
    install(Authentication) {
        jwt(AUTH_JWT) {
            realm = cfg.property("jwt.realm").getString()
            verifier(JWT.require(Algorithm.HMAC256(secret)).withAudience(audience).withIssuer(issuer).build())
            validate { credential -> if (credential.payload.audience.contains(audience)) JWTPrincipal(credential.payload) else null }
        }
    }
}
