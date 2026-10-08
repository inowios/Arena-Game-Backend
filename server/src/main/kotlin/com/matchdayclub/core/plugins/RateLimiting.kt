package com.matchdayclub.core.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import kotlin.time.Duration.Companion.minutes

val AUTH_RATE_LIMIT = RateLimitName("auth")
val API_RATE_LIMIT = RateLimitName("api")

fun Application.configureRateLimiting() {
    install(RateLimit) {
        register(AUTH_RATE_LIMIT) {
            rateLimiter(limit = 30, refillPeriod = 1.minutes)
            requestKey { call -> call.request.header("X-Real-IP") ?: call.request.origin.remoteHost }
        }
        register(API_RATE_LIMIT) {
            rateLimiter(limit = 600, refillPeriod = 1.minutes)
            requestKey { call -> call.request.header("X-Real-IP") ?: call.request.origin.remoteHost }
        }
    }
}
