package com.matchdayclub.core.di

import com.matchdayclub.common.time.Clock
import com.matchdayclub.common.time.SystemClock
import com.matchdayclub.core.auth.*
import com.matchdayclub.data.repository.*
import com.matchdayclub.domain.model.AuthProvider
import com.matchdayclub.domain.repository.*
import com.matchdayclub.domain.usecase.auth.*
import com.matchdayclub.domain.usecase.club.*
import com.matchdayclub.domain.usecase.athlete.*
import com.matchdayclub.domain.usecase.league.*
import com.matchdayclub.domain.usecase.economy.*
import com.matchdayclub.core.push.FcmSender
import com.matchdayclub.domain.rules.Store
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
    single<ClubRepository> { ClubRepositoryImpl() }
    single<BuildingRepository> { BuildingRepositoryImpl() }
    single<ClubExistence> { val clubs = get<ClubRepository>(); ClubExistence { clubs.findByUserId(it) != null } }
    single { ClubService(get(), get(), get()) }
    single { CreateClubUseCase(get(), get(), get(), getAll<ClubInitializer>()) }
    single { GetClubUseCase(get()) }
    single { PlaceBuildingUseCase(get(), get(), get()) }
    single { UpgradeBuildingUseCase(get(), get(), get()) }
    single { CollectIncomeUseCase(get(), get(), get(), get(), get<EconomyService>()) }
    single { SpeedUpUseCase(get(), get(), get()) }
    single { RemoveBuildingUseCase(get(), get()) }

    single<AthleteRepository> { AthleteRepositoryImpl() }
    single<TrainingRepository> { TrainingRepositoryImpl() }
    single<LineupRepository> { LineupRepositoryImpl() }
    single<ClubInitializer>(qualifier = org.koin.core.qualifier.named("squad")) { StarterSquadInitializer(get(), get()) }
    single<ClubInitializer>(qualifier = org.koin.core.qualifier.named("league")) { JoinPublicLeagueInitializer(get(), get(), get()) }
    single<LeagueRepository> { LeagueRepositoryImpl() }
    single<FixtureRepository> { FixtureRepositoryImpl() }
    single { BotService(get(), get(), get(), get()) }
    single { LeagueService(get(), get(), get(), get(), get()) }
    single { LineupService(get(), get()) }
    single<EconomyRepository> { EconomyRepositoryImpl() }
    single<PushTokenRepository> { PushTokenRepositoryImpl() }
    single { EconomyService(get(), get(), get()) }
    single {
        val c = get<Application>().environment.config
        FcmSender(get(), get(), c.propertyOrNull("push.fcm.serviceAccountJson")?.getString(), c.propertyOrNull("push.fcm.projectId")?.getString())
    }
    single { MatchdayNotifier(get()) }
    single { MatchdayRunner(get(), get(), get(), get(), get(), get(), get(), get<EconomyService>(), get()) }
    single { ClaimDailyRewardUseCase(get(), get(), get(), get()) }
    single { SeasonPassUseCases(get(), get(), get(), get(), get()) }
    single<Map<Store, ReceiptVerifier>> {
        val c = get<Application>().environment.config
        val dev = c.propertyOrNull("iap.devMode")?.getString()?.toBoolean() ?: false
        if (dev) mapOf(Store.DEV to DevReceiptVerifier(), Store.GOOGLE to DevReceiptVerifier(), Store.APPLE to DevReceiptVerifier()) else emptyMap()
    }
    single { VerifyPurchaseUseCase(get(), get(), get(), get(), get(), get()) }
    single { AdRewardUseCase(get(), get(), get(), get(), get()) }
    single { LeagueQueries(get(), get(), get(), get(), get()) }
    single { CreatePrivateLeagueUseCase(get(), get(), get()) }
    single { JoinPrivateLeagueUseCase(get(), get()) }
    single { LeavePrivateLeagueUseCase(get(), get()) }
    single { PlayFriendlyUseCase(get(), get(), get(), get(), get(), get(), get()) }
    single { GetPublicClubUseCase(get(), get(), get()) }
    single { SquadService(get(), get(), get(), get()) }
    single { ListAthletesUseCase(get()) }
    single { ClaimAcademyUseCase(get(), get(), get(), get()) }
    single { StartTrainingUseCase(get(), get(), get(), get(), get(), get<EconomyService>()) }
    single { CompleteTrainingUseCase(get(), get(), get(), get()) }
    single { HealAthleteUseCase(get(), get(), get(), get(), get()) }
    single { GetLineupUseCase(get(), get(), get(), get()) }
    single { SetLineupUseCase(get(), get(), get(), get(), get<EconomyService>()) }

    single { TokenIssuer(get(), get(), get()) }
    single { GuestLoginUseCase(get(), get(), get()) }
    single { OAuthLoginUseCase(get(), get(), get(), get()) }
    single { RefreshTokenUseCase(get(), get(), get(), get()) }
    single { LogoutUseCase(get()) }
}
