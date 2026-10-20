package com.arenacity.presentation.route

import com.arenacity.common.response.toSuccessResponse
import com.arenacity.core.plugins.AUTH_JWT
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.repository.PushTokenRepository
import com.arenacity.domain.rules.Economy
import com.arenacity.domain.rules.Store
import com.arenacity.domain.rules.TaskKind
import com.arenacity.domain.usecase.economy.*
import com.arenacity.presentation.dto.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

private inline fun <reified E : Enum<E>> parseEnum(v: String, what: String): E = runCatching { enumValueOf<E>(v.uppercase()) }.getOrElse { throw ValidationException("Unknown $what '$v'") }

fun Route.economyRoutes() {
    val daily: ClaimDailyRewardUseCase by inject()
    val pass: SeasonPassUseCases by inject()
    val purchase: VerifyPurchaseUseCase by inject()
    val pushTokens: PushTokenRepository by inject()
    val clubService: com.arenacity.domain.usecase.club.ClubService by inject()
    val coinPacks: BuyCoinPackUseCase by inject()
    val achievements: AchievementUseCases by inject()
    val ssv: com.arenacity.core.store.AdMobSsvVerifier by inject()
    val log = org.slf4j.LoggerFactory.getLogger("Ads")

    get("/catalog/products") { call.respond(Economy.PRODUCTS.values.map { ProductDto(it.id, it.gold, it.coins, it.premiumPass) }.toSuccessResponse()) }
    get("/catalog/coin-packs") { call.respond(Economy.COIN_PACKS.values.map { CoinPackDto(it.id, it.gold, it.coins) }.toSuccessResponse()) }

    /**
     * AdMob rewarded-ad server-side verification callback (its URL is set in the AdMob console). No placement grants a
     * reward any more (the double income and the free speed-up went with the city builder, 17 October 2026): the signature
     * is still checked (403 when bad) and every valid callback answered 200, so AdMob stops retrying.
     */
    get("/ads/ssv") {
        val raw = call.request.queryString()
        val params = try { ssv.verify(raw) } catch (e: Exception) { log.warn("SSV rejected: ${e.message}"); com.arenacity.core.metrics.Metrics.inc("ad_rewards_total", "source" to "ssv", "result" to "rejected"); call.respond(io.ktor.http.HttpStatusCode.Forbidden, mapOf("ok" to false)); return@get }
        log.info("SSV ignored (no rewarded placements): user=${params["user_id"]} custom=${params["custom_data"].orEmpty()}")
        com.arenacity.core.metrics.Metrics.inc("ad_rewards_total", "source" to "ssv", "result" to "not_applied")
        call.respond(mapOf("ok" to true))
    }
    authenticate(AUTH_JWT) {
        post("/club/daily/claim") { val r = daily(call.userId()); call.respond(DailyClaimResponse(r.day, r.reward.toDto(), r.streak, r.club.toDto(), Economy.LOGIN_CALENDAR.map { it.toDto() }).toSuccessResponse()) }
        get("/club/pass") { call.respond(pass.view(call.userId()).toDto().toSuccessResponse()) }
        post("/club/pass/tasks/{task}/claim") { call.respond(pass.claimTask(call.userId(), parseEnum<TaskKind>(call.parameters["task"]!!, "task")).toDto().toSuccessResponse()) }
        post("/club/pass/claim") {
            val r = pass.claimLevels(call.userId())
            call.respond(ClaimLevelsResponse(r.reward.toDto(), r.view.toDto(), clubService.clubOf(call.userId()).toDto(), r.items).toSuccessResponse())
        }
        post("/club/shop/coins/{pack}") { call.respond(coinPacks(call.userId(), call.parameters["pack"]!!).toDto().toSuccessResponse()) }
        get("/club/achievements") { call.respond(achievements.list(call.userId()).map { it.toDto() }.toSuccessResponse()) }
        post("/club/achievements/{id}/claim") {
            val r = achievements.claim(call.userId(), call.parameters["id"]!!)
            call.respond(AchievementClaimResponse(r.gold, r.items, r.list.map { it.toDto() }, r.club.toDto()).toSuccessResponse())
        }
        post("/purchases/verify") {
            val r = call.receive<VerifyPurchaseRequest>()
            val store = parseEnum<Store>(r.store, "store")
            val res = try { purchase(call.userId(), store, r.productId, r.token) }
                      catch (e: Exception) { com.arenacity.core.metrics.Metrics.inc("purchases_total", "store" to store.name, "result" to "rejected"); throw e }
            com.arenacity.core.metrics.Metrics.inc("purchases_total", "store" to store.name, "result" to res.status.lowercase())
            call.respond(PurchaseResponse(res.status, res.gold, res.coins, res.club.toDto()).toSuccessResponse())
        }
        post("/push-tokens") { val r = call.receive<PushTokenRequest>(); pushTokens.upsert(call.userId(), r.token, r.platform.lowercase()); call.respond(mapOf("ok" to true).toSuccessResponse()) }
        delete("/push-tokens") { val r = call.receive<PushTokenRequest>(); pushTokens.delete(r.token); call.respond(mapOf("ok" to true).toSuccessResponse()) }
    }
}
