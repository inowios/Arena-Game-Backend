package com.matchdayclub.core.store

import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.usecase.economy.RefundAction
import kotlinx.serialization.json.*
import java.security.cert.X509Certificate
import java.util.Base64

/** A store told us a purchase was refunded (or that a refund was reversed). [kind] is the store's own type, for logs. */
data class StoreRefundEvent(val action: RefundAction, val externalIds: List<String>, val kind: String)

/**
 * The store webhooks (POST /api/v1/store/...). Apple: on unless neither the root certificate nor dev mode is
 * configured — without [appleRoots] the signed payload is NOT verified, so only dev mode allows that. Google: the
 * Pub/Sub push must carry `?token=<googleToken>`; an empty token keeps the endpoint closed.
 */
data class StoreNotificationsConfig(
    val appleEnabled: Boolean,
    val appleRoots: List<X509Certificate>,
    val appleBundleId: String,
    val googleToken: String,
    val googlePackageName: String,
)

/**
 * App Store Server Notifications V2: the body is `{"signedPayload": <JWS>}`; the payload carries `notificationType`
 * and `data.signedTransactionInfo` (another JWS) with the transaction id — the same id the app sends to
 * /purchases/verify, so the purchase is found by "apple:<transactionId>".
 */
object AppStoreNotifications {
    /** Returns null for notification types that are not about refunds (renewals, offers, …). */
    fun parse(body: String, roots: List<X509Certificate>, bundleId: String): StoreRefundEvent? {
        val signed = json(body)["signedPayload"]?.jsonPrimitive?.contentOrNull ?: throw ValidationException("signedPayload missing")
        val payload = Jws.payload(signed, roots)
        val type = payload["notificationType"]?.jsonPrimitive?.contentOrNull ?: throw ValidationException("notificationType missing")
        val action = when (type) { "REFUND", "REVOKE" -> RefundAction.REFUND; "REFUND_REVERSED" -> RefundAction.RESTORE; else -> return null }
        val data = payload["data"]?.jsonObject ?: throw ValidationException("data missing")
        if (bundleId.isNotEmpty() && data["bundleId"]?.jsonPrimitive?.contentOrNull != bundleId) throw ValidationException("Notification is for another app")
        val txInfo = data["signedTransactionInfo"]?.jsonPrimitive?.contentOrNull ?: throw ValidationException("signedTransactionInfo missing")
        val tx = Jws.payload(txInfo, roots)
        val id = tx["transactionId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw ValidationException("transactionId missing")
        return StoreRefundEvent(action, listOf("apple:$id"), type)
    }
}

/**
 * Google Play Real-time developer notifications delivered by a Pub/Sub push subscription: `message.data` is the
 * base64 notification; only `voidedPurchaseNotification` matters here. The purchase is found by "google:<orderId>"
 * or, for purchases verified without an order id, by the hash of the purchase token.
 */
object GooglePlayNotifications {
    fun parse(body: String, packageName: String): StoreRefundEvent? {
        val message = json(body)["message"]?.jsonObject ?: throw ValidationException("message missing")
        val data = message["data"]?.jsonPrimitive?.contentOrNull ?: throw ValidationException("message.data missing")
        val n = json(String(Base64.getDecoder().decode(data)))
        if (packageName.isNotEmpty() && n["packageName"]?.jsonPrimitive?.contentOrNull != packageName) throw ValidationException("Notification is for another app")
        val voided = n["voidedPurchaseNotification"]?.jsonObject ?: return null
        val ids = listOfNotNull(
            voided["orderId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { "google:$it" },
            voided["purchaseToken"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { "google:" + sha256Hex(it) },
        )
        if (ids.isEmpty()) throw ValidationException("voided purchase without an order id or token")
        return StoreRefundEvent(RefundAction.REFUND, ids, "VOIDED_" + (voided["refundType"]?.jsonPrimitive?.intOrNull ?: 0))
    }
}

private fun json(s: String): JsonObject = try { Json.parseToJsonElement(s).jsonObject } catch (e: Exception) { throw ValidationException("Malformed notification") }
