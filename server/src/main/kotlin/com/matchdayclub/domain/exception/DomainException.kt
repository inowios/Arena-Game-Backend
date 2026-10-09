package com.matchdayclub.domain.exception

import io.ktor.http.HttpStatusCode

sealed class DomainException(override val message: String, val statusCode: HttpStatusCode) : RuntimeException(message)
class ValidationException(message: String) : DomainException(message, HttpStatusCode.BadRequest)
class AuthenticationException(message: String = "Invalid credentials") : DomainException(message, HttpStatusCode.Unauthorized)
class AuthorizationException(message: String = "Access denied") : DomainException(message, HttpStatusCode.Forbidden)
class NotFoundException(message: String) : DomainException(message, HttpStatusCode.NotFound)
class ConflictException(message: String) : DomainException(message, HttpStatusCode.Conflict)
class InsufficientFundsException(message: String) : DomainException(message, HttpStatusCode.PaymentRequired)
/** A store / ad network / other upstream service failed; the client should retry later. */
class UpstreamException(message: String) : DomainException(message, HttpStatusCode.ServiceUnavailable)
