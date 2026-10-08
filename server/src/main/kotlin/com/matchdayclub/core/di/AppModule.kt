package com.matchdayclub.core.di

import com.matchdayclub.common.time.Clock
import com.matchdayclub.common.time.SystemClock
import com.matchdayclub.core.auth.*
import com.matchdayclub.data.repository.*
import com.matchdayclub.domain.model.AuthProvider
import com.matchdayclub.domain.repository.*
import com.matchdayclub.domain.usecase.auth.*
import com.matchdayclub.presentation.route.ClubExistence
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import kotlinx.serialization.json.Json
import org.koin.dsl.module

val appModule = module {
    single<Clock> { SystemClock }
    single {
        val c = get<Application>().environment.config
        JwtUtil(
            secret = c.property("jwt.secret").getString(), issuer = c.property("jwt.issuer").getString(), audience = c.property("jwt.audience").getString(),
            accessTokenExpiryMs = c.property("jwt.accessTokenExpiryMs").getString().toLong(), refreshTokenExpiryMs = c.property("jwt.refreshTokenExpiryMs").getString().toLong(),
        )
    }
    single {
        HttpClient(CIO) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
            install(HttpTimeout) { requestTimeoutMillis = 10_000 }
            expectSuccess = false
        }
    }
    single<Map<AuthProvider, IdTokenVerifier>> {
        val c = get<Application>().environment.config
        val googleIds = c.property("oauth.google.clientIds").getString().split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        mapOf(
            AuthProvider.GOOGLE to GoogleTokenVerifier(get(), googleIds),
            AuthProvider.APPLE to AppleTokenVerifier(get(), c.property("oauth.apple.bundleId").getString()),
        )
    }

    single<UserRepository> { UserRepositoryImpl() }
    single<RefreshTokenRepository> { RefreshTokenRepositoryImpl() }
    single<ClubExistence> { ClubExistence { false } }

    single { TokenIssuer(get(), get(), get()) }
    single { GuestLoginUseCase(get(), get(), get()) }
    single { OAuthLoginUseCase(get(), get(), get(), get()) }
    single { RefreshTokenUseCase(get(), get(), get(), get()) }
    single { LogoutUseCase(get()) }
}
