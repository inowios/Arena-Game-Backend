package com.arenacity.core.plugins

import com.arenacity.common.i18n.ServerI18n
import com.arenacity.common.response.ApiResponse
import com.arenacity.domain.exception.DomainException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<DomainException> { call, cause ->
            val lang = ServerI18n.fromHeader(call.request.headers[HttpHeaders.AcceptLanguage]) ?: "en"
            // technical texts that aren't in the catalog: a generic, translated line for service errors, English otherwise
            val text = if (!ServerI18n.has(cause.template) && cause.statusCode.value >= 500) ServerI18n.t("Service temporarily unavailable. Try again later.", lang) else cause.localized(lang)
            call.respond(cause.statusCode, ApiResponse<Nothing>(cause.statusCode.value, errors = listOf(text), requestId = call.attributes.getOrNull(RequestIdKey)))
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
            val lang = ServerI18n.fromHeader(call.request.headers[HttpHeaders.AcceptLanguage]) ?: "en"
            call.respond(HttpStatusCode.InternalServerError, ApiResponse<Nothing>(500, errors = listOf(ServerI18n.t("Something went wrong. Try again.", lang)), requestId = requestId))
        }
    }
}
