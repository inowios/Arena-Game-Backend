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
    single<com.matchdayclub.common.tx.Tx> { com.matchdayclub.common.tx.DbTx }
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
    single<ClubExistence> { val clubs = get<ClubRepository>(); ClubExistence { clubs.findByUserId(it)?.isBot == false } }
    single<com.matchdayclub.domain.repository.ObstacleRepository> { com.matchdayclub.data.repository.ObstacleRepositoryImpl() }
    single {
        ClubService(get(), get(), get(), getAll<BuildingListener>(), get()).also { svc ->
            val koin = getKoin()
            svc.effects = { koin.get<com.matchdayclub.domain.usecase.liveops.LiveOps>().modifiers().let { com.matchdayclub.domain.usecase.club.EventEffects(it.buildTimePct, it.incomePct, it.obstacleGoldPct) } }
        }
    }
    single<com.matchdayclub.domain.usecase.liveops.LiveOpsRepository> { com.matchdayclub.data.repository.LiveOpsRepositoryImpl() }
    single { val econ: EconomyRepository = get(); com.matchdayclub.domain.usecase.liveops.LiveOps(get(), get(), get(), get(), get(), tx = get(), purchases = { club, pid -> econ.hasPurchase(club, pid) }) }
    single { SectionService(get(), get()) }
    single<BuildingListener>(qualifier = org.koin.core.qualifier.named("sectionSquad")) { SectionSquadOnCourt(get(), get()) }
    single { CreateClubUseCase(get(), get(), get(), getAll<ClubInitializer>(), tx = get()) }
    single { GetClubUseCase(get()) }
    single { PlaceBuildingUseCase(get(), get(), get(), tx = get(), cosmetics = get()) }
    single { UpgradeBuildingUseCase(get(), get(), get(), tx = get()) }
    single { val cos: CosmeticsService = get(); CollectIncomeUseCase(get(), get(), get(), get(), get<EconomyService>(), tx = get(), collections = { id -> com.matchdayclub.domain.rules.Collections.incomePct(cos.owned(id)) }) }
    single<com.matchdayclub.domain.usecase.progress.RoadRepository> { com.matchdayclub.data.repository.RoadRepositoryImpl() }
    single { com.matchdayclub.domain.usecase.progress.RoadUseCases(get(), get(), get(), get(), get(), get(), tx = get()) }
    single { com.matchdayclub.domain.usecase.progress.CollectionsUseCases(get(), get()) }
    single { com.matchdayclub.domain.usecase.progress.MuseumService(get(), get(), get()) }
    single { SpeedUpUseCase(get(), get(), get(), tx = get()) }
    single { RemoveBuildingUseCase(get(), get(), tx = get()) }
    single { MoveBuildingUseCase(get(), get(), get(), tx = get()) }
    single { PlaceFencesUseCase(get(), get(), get(), tx = get()) }
    single { UpgradeFencesUseCase(get(), get(), get(), tx = get()) }
    single { ApplyLayoutUseCase(get(), get(), get(), tx = get()) }
    single<com.matchdayclub.domain.repository.LayoutRepository> { com.matchdayclub.data.repository.LayoutRepositoryImpl() }
    single { LayoutUseCases(get(), get(), get(), get(), tx = get()) }
    single { BuyBuilderUseCase(get(), get(), get(), tx = get()) }
    single { ClearObstacleUseCase(get(), get(), get(), tx = get()) }
    single { SpeedUpObstacleUseCase(get(), get(), get(), tx = get()) }
    single { CollectObstacleUseCase(get(), get(), get(), get(), get<EconomyService>(), tx = get()) }
    single { UpdateClubUseCase(get(), get(), get(), tx = get()) }

    single<AthleteRepository> { AthleteRepositoryImpl() }
    single<TrainingRepository> { TrainingRepositoryImpl() }
    single<LineupRepository> { LineupRepositoryImpl() }
    single<ClubInitializer>(qualifier = org.koin.core.qualifier.named("squad")) { StarterSquadInitializer(get(), get()) }
    single<ClubInitializer>(qualifier = org.koin.core.qualifier.named("league")) { JoinPublicLeagueInitializer(get(), get(), get()) }
    single<LeagueRepository> { LeagueRepositoryImpl() }
    single<FixtureRepository> { FixtureRepositoryImpl() }
    single { BotService(get(), get(), get(), get(), get()) }
    single<SeasonRepository> { SeasonRepositoryImpl() }
    single { CareerService(get(), get(), get()) }
    single { ReleaseAthleteUseCase(get(), get(), get(), get(), tx = get()) }
    single { UpgradeTechniqueUseCase(get(), get(), get(), tx = get()) }
    single { ScoutingUseCases(get(), get(), get(), get(), get(), tx = get()) }
    single<com.matchdayclub.domain.repository.PackRepository> { com.matchdayclub.data.repository.PackRepositoryImpl() }
    single { PackUseCases(get(), get(), get(), get(), get(), tx = get()) }
    single { CardMatchUseCases(get(), get(), get(), get(), tx = get()) }
    single { SeasonService(get(), get(), get(), get(), get(), get(), get<FcmSender>(), inbox = get(), careers = get()) }
    single { GetClubHistoryUseCase(get(), get()) }
    single { LeagueService(get(), get(), get(), get(), get()) }
    single { LineupService(get(), get()) }
    single<EconomyRepository> { EconomyRepositoryImpl() }
    single<PushTokenRepository> { PushTokenRepositoryImpl() }
    single {
        val sections = get<SectionService>(); val clubs = get<ClubRepository>()
        EconomyService(get(), get(), get(), { id -> clubs.findById(id)?.let { sections.active(it).size } ?: 1 }, achievements = get())
            .also { e -> val koin = getKoin(); e.also = listOf(com.matchdayclub.domain.usecase.alliance.AllianceProgress { koin.get() }, com.matchdayclub.domain.usecase.liveops.EventProgressTracker { koin.get() }) }
    }
    single<com.matchdayclub.domain.usecase.alliance.AllianceRepository> { com.matchdayclub.data.repository.AllianceRepositoryImpl() }
    single { com.matchdayclub.domain.usecase.alliance.AllianceUseCases(get(), get(), get(), get(), get(), tx = get()) }
    single {
        val c = get<Application>().environment.config
        FcmSender(get(), get(), c.propertyOrNull("push.fcm.serviceAccountJson")?.getString(), c.propertyOrNull("push.fcm.projectId")?.getString(), get<UserRepository>())
    }
    single { MatchdayNotifier(get(), get()) }
    single { MatchdayRunner(get(), get(), get(), get(), get(), get(), get(), get<EconomyService>(), get(), tx = get(), sections = get(), seasonCloser = get<SeasonService>(), failures = com.matchdayclub.core.plugins.SchedulerFailures) }
    single { ClaimDailyRewardUseCase(get(), get(), get(), get(), tx = get()) }
    single { SeasonPassUseCases(get(), get(), get(), get(), get(), tx = get(), cosmetics = get(), athletes = get()) }
    single<com.matchdayclub.core.store.HttpGateway> { com.matchdayclub.core.store.KtorHttpGateway(get()) }
    single<Map<Store, ReceiptVerifier>> {
        val c = get<Application>().environment.config
        fun prop(k: String) = c.propertyOrNull(k)?.getString()?.trim().orEmpty()
        val dev = prop("iap.devMode").toBoolean()
        val log = org.slf4j.LoggerFactory.getLogger("Store")
        buildMap {
            if (dev) put(Store.DEV, DevReceiptVerifier())
            val playJson = prop("iap.google.serviceAccountJson")
            if (playJson.isNotEmpty()) put(Store.GOOGLE, com.matchdayclub.core.store.GooglePlayVerifier(get(),
                com.matchdayclub.core.store.GoogleServiceAccount(get(), playJson, "https://www.googleapis.com/auth/androidpublisher"), prop("iap.google.packageName")))
            else if (dev) put(Store.GOOGLE, DevReceiptVerifier())
            val issuer = prop("iap.apple.issuerId"); val keyId = prop("iap.apple.keyId"); val pk = prop("iap.apple.privateKey")
            if (issuer.isNotEmpty() && keyId.isNotEmpty() && pk.isNotEmpty()) {
                put(Store.APPLE, com.matchdayclub.core.store.AppStoreVerifier(get(), issuer, keyId, pk, prop("iap.apple.bundleId"), appleRoots(c)))
            } else if (dev) put(Store.APPLE, DevReceiptVerifier())
            log.info("Receipt verifiers: ${keys.joinToString()}${if (dev) " (dev receipts accepted)" else ""}")
        }
    }
    single { VerifyPurchaseUseCase(get(), get(), get(), get(), get(), get(), tx = get(), cosmetics = get()) }
    single { com.matchdayclub.domain.usecase.economy.RefundUseCase(get(), get(), get(), get(), tx = get(), inbox = get(), events = get()) }
    single {
        val c = get<Application>().environment.config
        fun prop(k: String) = c.propertyOrNull(k)?.getString()?.trim().orEmpty()
        val dev = prop("iap.devMode").toBoolean(); val roots = appleRoots(c); val token = prop("iap.google.rtdnToken")
        val log = org.slf4j.LoggerFactory.getLogger("Store")
        if (roots.isEmpty() && dev) log.warn("App Store notifications are accepted WITHOUT signature checks (dev mode, no APPSTORE_ROOT_CERT_PATH)")
        log.info("Store notifications: apple ${if (roots.isNotEmpty() || dev) "on" else "off (set APPSTORE_ROOT_CERT_PATH)"}, google ${if (token.isNotEmpty()) "on" else "off (set GOOGLE_PLAY_RTDN_TOKEN)"}")
        com.matchdayclub.core.store.StoreNotificationsConfig(appleEnabled = roots.isNotEmpty() || dev, appleRoots = roots, appleBundleId = prop("iap.apple.bundleId"), googleToken = token, googlePackageName = prop("iap.google.packageName"))
    }
    single { AdRewardUseCase(get(), get(), get(), get(), get(), tx = get(), repo = get()) }
    single { com.matchdayclub.core.store.AdMobSsvVerifier(get()) }
    single {
        val app = get<Application>()
        val v = app.environment.config.propertyOrNull("ads.clientRewards")?.getString()?.trim().orEmpty()
        com.matchdayclub.presentation.route.AdsConfig(if (v.isEmpty()) app.developmentMode else v.toBoolean())
    }
    single<InventoryRepository> { InventoryRepositoryImpl() }
    single<AchievementRepository> { AchievementRepositoryImpl() }
    single { CosmeticsService(get(), get()) }
    single { CosmeticsUseCases(get(), get(), get(), get(), get(), tx = get()) }
    single { BuyCoinPackUseCase(get(), get(), get(), tx = get()) }
    single { AchievementService(get(), get(), get(), get()) }
    single { AchievementUseCases(get(), get(), get(), get(), get(), get(), tx = get()) }
    single { SpeedUpTrainingUseCase(get(), get(), get(), get(), tx = get()) }
    single { SpeedUpMedicalUseCase(get(), get(), get(), tx = get()) }
    single { LeagueQueries(get(), get(), get(), get(), get()) }
    single { CreatePrivateLeagueUseCase(get(), get(), get()) }
    single { JoinPrivateLeagueUseCase(get(), get(), tx = get(), invites = get(), chat = get()) }
    single { LeavePrivateLeagueUseCase(get(), get(), tx = get(), chat = get()) }
    single<ChatRepository> { ChatRepositoryImpl() }
    single<SocialRepository> { SocialRepositoryImpl() }
    single { val koin = getKoin(); com.matchdayclub.domain.usecase.social.ChatService(get(), get(), get(), get(), get(), tx = get(), channels = { club, ch -> koin.get<com.matchdayclub.domain.usecase.alliance.AllianceUseCases>().isMember(club, ch) }) }
    single { com.matchdayclub.domain.usecase.social.PrivateLeagueAdmin(get(), get(), get(), get(), get(), get(), get(), tx = get()) }
    single { com.matchdayclub.domain.usecase.social.HeadToHeadUseCase(get(), get(), get()) }
    single { com.matchdayclub.domain.usecase.social.GetGroundsUseCase(get(), get(), get()) }
    single { com.matchdayclub.domain.usecase.social.ChallengeUseCases(get(), get(), get(), get(), get(), get(), get(), tx = get(), push = get(), inbox = get()) }
    single { com.matchdayclub.domain.usecase.social.InviteTracker(get(), get(), get(), get(), progress = get<EconomyService>(), inbox = get()) }
    single { PlayFriendlyUseCase(get(), get(), get(), get(), get(), get(), get(), tx = get()) }
    single<com.matchdayclub.domain.repository.LiveMatchRepository> { com.matchdayclub.data.repository.LiveMatchRepositoryImpl() }
    single { com.matchdayclub.domain.usecase.live.FinishLiveMatchUseCase(get(), get(), get(), get(), get(), progress = get<EconomyService>(), inbox = get(), tx = get(), buildings = get()) }
    single { com.matchdayclub.domain.usecase.live.StartLiveMatchUseCase(get(), get(), get(), get(), get(), get(), get(), get(), tx = get(), buildings = get()) }
    single { com.matchdayclub.domain.usecase.live.CancelLiveMatchUseCase(get(), get(), get(), tx = get()) }
    single { com.matchdayclub.domain.usecase.live.RecentLiveMatchesUseCase(get(), get(), get()) }
    single { com.matchdayclub.domain.usecase.live.AthleteMatchHistoryUseCase(get(), get(), get(), get()) }
    single { com.matchdayclub.domain.usecase.live.LiveReadinessUseCase(get(), get(), get(), get(), get()) }
    single { com.matchdayclub.domain.usecase.live.ArenaChestUseCase(get(), get(), get(), tx = get()) }
    single<com.matchdayclub.domain.repository.MarketRepository> { com.matchdayclub.data.repository.MarketRepositoryImpl() }
    single { com.matchdayclub.domain.usecase.market.MarketService(get(), get(), get()) }
    single { com.matchdayclub.domain.usecase.market.GetMarketUseCase(get(), get()) }
    single { com.matchdayclub.domain.usecase.market.BuyListingUseCase(get(), get(), get(), get(), get(), get(), tx = get()) }
    single { com.matchdayclub.domain.usecase.market.RefreshMarketUseCase(get(), get(), get(), get(), tx = get()) }
    single { com.matchdayclub.domain.usecase.market.SellAthleteUseCase(get(), get(), get(), get(), get(), tx = get()) }
    single { com.matchdayclub.domain.usecase.live.RestSquadUseCase(get(), get(), get(), get(), get(), tx = get()) }
    single { GetPublicClubUseCase(get(), get(), get(), get()) }
    single { SquadService(get(), get(), get(), get()) }
    single { ListAthletesUseCase(get()) }
    single { ClaimAcademyUseCase(get(), get(), get(), get(), tx = get(), sections = get(), progress = get<EconomyService>()) }
    single { StartTrainingUseCase(get(), get(), get(), get(), get(), get<EconomyService>(), tx = get()) }
    single { CompleteTrainingUseCase(get(), get(), get(), get(), tx = get()) }
    single { HealAthleteUseCase(get(), get(), get(), get(), get(), tx = get()) }
    single { GetLineupUseCase(get(), get(), get(), get()) }
    single { SetLineupUseCase(get(), get(), get(), get(), get<EconomyService>()) }

    single { TokenIssuer(get(), get(), get()) }
    single<AccountRepository> { AccountRepositoryImpl() }
    single<InboxRepository> { InboxRepositoryImpl() }
    single { com.matchdayclub.domain.usecase.account.InboxService(get(), get()) }
    single { com.matchdayclub.domain.usecase.account.ClubRetirement(get(), get(), get(), get()) }
    single { com.matchdayclub.domain.usecase.account.TransferUseCases(get(), get(), get(), get(), get(), get(), tx = get()) }
    single { com.matchdayclub.domain.usecase.account.DeleteAccountUseCase(get(), get(), get(), get(), get(), get(), tx = get(), analytics = get(), events = get()) }
    single<AnalyticsRepository> { AnalyticsRepositoryImpl() }
    single { com.matchdayclub.domain.usecase.analytics.ActivityTracker(get(), get()) }
    single { com.matchdayclub.domain.usecase.analytics.EventLog(get(), get()) }
    single { com.matchdayclub.domain.usecase.analytics.TrackEventsUseCase(get(), get()) }
    single { com.matchdayclub.domain.usecase.analytics.MetricsReportUseCase(get(), get()) }
    single { com.matchdayclub.domain.usecase.analytics.FunnelUseCase(get(), get()) }
    single { com.matchdayclub.domain.usecase.analytics.BalanceUseCase(get(), get()) }
    single<ClubInitializer>(qualifier = org.koin.core.qualifier.named("analytics")) { com.matchdayclub.domain.usecase.analytics.AnalyticsClubInitializer(get()) }
    single<OpsRepository> { OpsRepositoryImpl(get()) }
    single { com.matchdayclub.domain.usecase.admin.SupportQueries(get(), get(), get()) }
    single { com.matchdayclub.domain.usecase.admin.AdminGrantUseCase(get(), get(), get(), get(), tx = get()) }
    single<CupRepository> { CupRepositoryImpl() }
    single { com.matchdayclub.domain.usecase.cup.CupNotifier(get<FcmSender>(), get()) }
    single { com.matchdayclub.domain.usecase.cup.CupService(get(), get(), get(), get(), get(), get(), get(), tx = get(), notifier = get(), failures = com.matchdayclub.core.plugins.SchedulerFailures) }
    single { com.matchdayclub.domain.usecase.cup.CupQueries(get(), get(), get(), get(), get()) }
    single { com.matchdayclub.domain.usecase.analytics.AnalyticsRetention(get(), get()) }
    single { com.matchdayclub.core.plugins.SchedulerTick(get(), get(), get()) }
    single { GuestLoginUseCase(get(), get(), get()) }
    single { OAuthLoginUseCase(get(), get(), get(), get()) }
    single { RefreshTokenUseCase(get(), get(), get(), get()) }
    single { LogoutUseCase(get()) }
}

/** Apple Root CA - G3 from `iap.apple.rootCertPath` (verifies signed transactions and server notifications); empty when not configured. */
private fun appleRoots(c: io.ktor.server.config.ApplicationConfig): List<java.security.cert.X509Certificate> =
    c.propertyOrNull("iap.apple.rootCertPath")?.getString()?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
        java.io.File(path).inputStream().use { listOf(java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it) as java.security.cert.X509Certificate) }
    } ?: emptyList()
