package com.matchdayclub.presentation.route

import com.matchdayclub.common.response.toSuccessResponse
import com.matchdayclub.core.plugins.AUTH_JWT
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.repository.PushTokenRepository
import com.matchdayclub.domain.rules.Economy
import com.matchdayclub.domain.rules.Store
import com.matchdayclub.domain.rules.TaskKind
import com.matchdayclub.domain.usecase.economy.*
import com.matchdayclub.presentation.dto.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import java.util.UUID

private inline fun <reified E : Enum<E>> parseEnum(v: String, what: String): E = runCatching { enumValueOf<E>(v.uppercase()) }.getOrElse { throw ValidationException("Unknown $what '$v'") }

fun Route.economyRoutes() {
    val daily: ClaimDailyRewardUseCase by inject()
    val pass: SeasonPassUseCases by inject()
    val purchase: VerifyPurchaseUseCase by inject()
    val ads: AdRewardUseCase by inject()
    val pushTokens: PushTokenRepository by inject()
    val clubService: com.matchdayclub.domain.usecase.club.ClubService by inject()

    get("/catalog/products") { call.respond(Economy.PRODUCTS.values.map { ProductDto(it.id, it.gold, it.coins, it.premiumPass) }.toSuccessResponse()) }
    authenticate(AUTH_JWT) {
        post("/club/daily/claim") { val r = daily(call.userId()); call.respond(DailyClaimResponse(r.day, r.reward.toDto(), r.streak, r.club.toDto(), Economy.LOGIN_CALENDAR.map { it.toDto() }).toSuccessResponse()) }
        get("/club/pass") { call.respond(pass.view(call.userId()).toDto().toSuccessResponse()) }
        post("/club/pass/tasks/{task}/claim") { call.respond(pass.claimTask(call.userId(), parseEnum<TaskKind>(call.parameters["task"]!!, "task")).toDto().toSuccessResponse()) }
        post("/club/pass/claim") {
            val (reward, view) = pass.claimLevels(call.userId())
            call.respond(ClaimLevelsResponse(reward.toDto(), view.toDto(), clubService.clubOf(call.userId()).toDto()).toSuccessResponse())
        }
        post("/purchases/verify") {
            val r = call.receive<VerifyPurchaseRequest>()
            val res = purchase(call.userId(), parseEnum<Store>(r.store, "store"), r.productId, r.token)
            call.respond(PurchaseResponse(res.status, res.gold, res.coins, res.club.toDto()).toSuccessResponse())
        }
        post("/ads/reward") {
            val r = call.receive<AdRewardRequest>()
            val club = ads(call.userId(), parseEnum<AdPlacement>(r.placement, "placement"), r.buildingId?.let { runCatching { UUID.fromString(it) }.getOrElse { throw ValidationException("Bad buildingId") } })
            call.respond(club.toDto().toSuccessResponse())
        }
        post("/push-tokens") { val r = call.receive<PushTokenRequest>(); pushTokens.upsert(call.userId(), r.token, r.platform.lowercase()); call.respond(mapOf("ok" to true).toSuccessResponse()) }
        delete("/push-tokens") { val r = call.receive<PushTokenRequest>(); pushTokens.delete(r.token); call.respond(mapOf("ok" to true).toSuccessResponse()) }
    }
}
