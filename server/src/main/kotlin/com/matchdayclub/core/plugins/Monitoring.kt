package com.matchdayclub.core.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import org.slf4j.event.Level

fun Application.configureMonitoring() {
    install(CallLogging) {
        level = Level.INFO
        format { call -> "[${call.attributes.getOrNull(RequestIdKey) ?: "-"}] ${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()}" }
    }
}
