package com.matchdayclub.core.plugins

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.util.*
import java.util.UUID

val RequestIdKey = AttributeKey<String>("RequestId")

fun Application.configureRequestId() {
    intercept(ApplicationCallPipeline.Setup) {
        val id = call.request.header("X-Request-Id") ?: UUID.randomUUID().toString()
        call.attributes.put(RequestIdKey, id)
        call.response.headers.append("X-Request-Id", id)
    }
}
