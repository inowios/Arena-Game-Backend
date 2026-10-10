package com.matchdayclub.presentation.route

import com.matchdayclub.core.metrics.Metrics
import com.matchdayclub.core.store.AppStoreNotifications
import com.matchdayclub.core.store.GooglePlayNotifications
import com.matchdayclub.core.store.StoreNotificationsConfig
import com.matchdayclub.core.store.StoreRefundEvent
import com.matchdayclub.domain.usecase.economy.RefundUseCase
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import java.security.MessageDigest

/**
 * Store webhooks for refunds (no JWT: the stores call them). Apple: App Store Connect → App Information → App Store
 * Server Notifications → `https://<host>/api/v1/store/apple/notifications` (V2). Google: Play Console → Monetisation
 * setup → Real-time developer notifications → a Pub/Sub topic with a push subscription to
 * `https://<host>/api/v1/store/google/rtdn?token=<GOOGLE_PLAY_RTDN_TOKEN>`. Always 200 once the message is read, so
 * the stores stop resending; 403 for a bad signature or token.
 */
fun Route.storeNotificationRoutes() {
    val refunds: RefundUseCase by inject()
    val cfg: StoreNotificationsConfig by inject()
    val log = org.slf4j.LoggerFactory.getLogger("StoreNotifications")

    suspend fun ApplicationCall.handle(store: String, parse: (String) -> StoreRefundEvent?) {
        val event = try { parse(receiveText()) } catch (e: Exception) {
            log.warn("$store notification rejected: ${e.message}"); Metrics.inc("store_notifications_total", "store" to store, "result" to "rejected")
            respond(HttpStatusCode.Forbidden, mapOf("ok" to false)); return
        }
        val result = if (event == null) "ignored" else refunds(event.action, event.externalIds).name.lowercase()
        Metrics.inc("store_notifications_total", "store" to store, "result" to result)
        if (event != null) log.info("$store ${event.kind} ${event.externalIds.first()}: $result")
        respond(HttpStatusCode.OK, mapOf("ok" to true))
    }

    route("/store") {
        post("/apple/notifications") {
            if (!cfg.appleEnabled) { call.respond(HttpStatusCode.NotFound, mapOf("ok" to false)); return@post }
            call.handle("apple") { AppStoreNotifications.parse(it, cfg.appleRoots, cfg.appleBundleId) }
        }
        post("/google/rtdn") {
            val given = call.request.queryParameters["token"].orEmpty()
            if (cfg.googleToken.isEmpty() || !MessageDigest.isEqual(given.toByteArray(), cfg.googleToken.toByteArray())) {
                Metrics.inc("store_notifications_total", "store" to "google", "result" to "rejected")
                call.respond(HttpStatusCode.Forbidden, mapOf("ok" to false)); return@post
            }
            call.handle("google") { GooglePlayNotifications.parse(it, cfg.googlePackageName) }
        }
    }
}
