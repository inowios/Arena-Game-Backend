package com.matchdayclub.core.plugins

import com.matchdayclub.common.response.ApiResponse
import com.matchdayclub.domain.exception.DomainException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<DomainException> { call, cause ->
            call.respond(cause.statusCode, ApiResponse<Nothing>(cause.statusCode.value, errors = listOf(cause.message), requestId = call.attributes.getOrNull(RequestIdKey)))
        }
        exception<RequestValidationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiResponse<Nothing>(400, errors = cause.reasons, requestId = call.attributes.getOrNull(RequestIdKey)))
        }
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ApiResponse<Nothing>(400, errors = listOf(cause.cause?.message ?: cause.message ?: "Bad request"), requestId = call.attributes.getOrNull(RequestIdKey)))
        }
        exception<Throwable> { call, cause ->
            val requestId = call.attributes.getOrNull(RequestIdKey)
            call.application.environment.log.error("Unhandled exception [requestId=$requestId]", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiResponse<Nothing>(500, errors = listOf("Internal server error"), requestId = requestId))
        }
    }
}
