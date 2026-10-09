package com.matchdayclub.domain.exception

import com.matchdayclub.common.i18n.ServerI18n
import io.ktor.http.HttpStatusCode

/**
 * Errors shown to players. [template] is the English text with {0}, {1}… placeholders and also the catalog key
 * (resources/i18n/messages.tsv) used to answer in the caller's language; [message] is the English text for logs.
 */
sealed class DomainException(val template: String, val statusCode: HttpStatusCode, val args: List<Any?> = emptyList()) :
    RuntimeException(ServerI18n.t(template, "en", *args.toTypedArray())) {
    fun localized(lang: String): String = ServerI18n.t(template, lang, *args.toTypedArray())
}
class ValidationException(message: String, vararg args: Any?) : DomainException(message, HttpStatusCode.BadRequest, args.toList())
class AuthenticationException(message: String = "Invalid credentials", vararg args: Any?) : DomainException(message, HttpStatusCode.Unauthorized, args.toList())
class AuthorizationException(message: String = "Access denied", vararg args: Any?) : DomainException(message, HttpStatusCode.Forbidden, args.toList())
class NotFoundException(message: String, vararg args: Any?) : DomainException(message, HttpStatusCode.NotFound, args.toList())
class ConflictException(message: String, vararg args: Any?) : DomainException(message, HttpStatusCode.Conflict, args.toList())
class InsufficientFundsException(message: String, vararg args: Any?) : DomainException(message, HttpStatusCode.PaymentRequired, args.toList())
/** A store / ad network / other upstream service failed; the client should retry later. */
class UpstreamException(message: String, vararg args: Any?) : DomainException(message, HttpStatusCode.ServiceUnavailable, args.toList())
