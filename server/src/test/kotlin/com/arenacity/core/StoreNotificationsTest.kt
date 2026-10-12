package com.arenacity.core

import com.arenacity.core.store.*
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.usecase.economy.RefundAction
import kotlinx.serialization.json.*
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.*

class StoreNotificationsTest {
    private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private val key: PrivateKey = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private

    /** An ES256 JWS without a certificate chain: accepted only while no roots are configured (dev mode). */
    private fun jws(payload: JsonObject): String {
        val signingInput = b64url("""{"alg":"ES256"}""".toByteArray()) + "." + b64url(payload.toString().toByteArray())
        val sig = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(key); update(signingInput.toByteArray()) }.sign()
        return "$signingInput.${b64url(sig)}"
    }

    private fun appleBody(type: String, bundle: String = "com.inowios.arenacity", transactionId: String = "2000000123456789"): String {
        val tx = buildJsonObject { put("transactionId", transactionId); put("bundleId", bundle); put("productId", "gold_small"); put("revocationDate", 1760000100000) }
        val payload = buildJsonObject {
            put("notificationType", type); put("notificationUUID", "u-1"); put("version", "2.0")
            putJsonObject("data") { put("bundleId", bundle); put("environment", "Sandbox"); put("signedTransactionInfo", jws(tx)) }
        }
        return buildJsonObject { put("signedPayload", jws(payload)) }.toString()
    }

    @Test
    fun `app store refund, revoke and reversal map to the purchase by transaction id`() {
        val refund = AppStoreNotifications.parse(appleBody("REFUND"), emptyList(), "com.inowios.arenacity")!!
        assertEquals(RefundAction.REFUND, refund.action); assertEquals(listOf("apple:2000000123456789"), refund.externalIds); assertEquals("REFUND", refund.kind)
        assertEquals(RefundAction.REFUND, AppStoreNotifications.parse(appleBody("REVOKE"), emptyList(), "")!!.action)
        assertEquals(RefundAction.RESTORE, AppStoreNotifications.parse(appleBody("REFUND_REVERSED"), emptyList(), "com.inowios.arenacity")!!.action)
        assertNull(AppStoreNotifications.parse(appleBody("DID_RENEW"), emptyList(), "com.inowios.arenacity"))
    }

    @Test
    fun `app store notifications for another app or without a payload are rejected`() {
        assertFailsWith<ValidationException> { AppStoreNotifications.parse(appleBody("REFUND", bundle = "com.other.app"), emptyList(), "com.inowios.arenacity") }
        assertFailsWith<ValidationException> { AppStoreNotifications.parse("""{"foo":1}""", emptyList(), "") }
        assertFailsWith<ValidationException> { AppStoreNotifications.parse("not json", emptyList(), "") }
        assertFailsWith<ValidationException> { AppStoreNotifications.parse("""{"signedPayload":"a.b"}""", emptyList(), "") }
    }

    private fun googleBody(notification: JsonObject) = buildJsonObject {
        putJsonObject("message") { put("data", Base64.getEncoder().encodeToString(notification.toString().toByteArray())); put("messageId", "1"); put("publishTime", "2026-10-10T10:00:00Z") }
        put("subscription", "projects/p/subscriptions/s")
    }.toString()

    @Test
    fun `google voided purchases are found by order id and token hash`() {
        val voided = buildJsonObject {
            put("version", "1.0"); put("packageName", "com.inowios.arenacity"); put("eventTimeMillis", "1760000000000")
            putJsonObject("voidedPurchaseNotification") { put("purchaseToken", "tok-1"); put("orderId", "GPA.1234-5678"); put("productType", 2); put("refundType", 1) }
        }
        val e = GooglePlayNotifications.parse(googleBody(voided), "com.inowios.arenacity")!!
        assertEquals(RefundAction.REFUND, e.action); assertEquals("VOIDED_1", e.kind)
        assertEquals(listOf("google:GPA.1234-5678", "google:" + sha256Hex("tok-1")), e.externalIds)
        // other notification kinds are ignored, other packages rejected
        val other = buildJsonObject { put("version", "1.0"); put("packageName", "com.inowios.arenacity"); putJsonObject("oneTimeProductNotification") { put("purchaseToken", "t"); put("notificationType", 1) } }
        assertNull(GooglePlayNotifications.parse(googleBody(other), "com.inowios.arenacity"))
        assertFailsWith<ValidationException> { GooglePlayNotifications.parse(googleBody(voided), "com.other.app") }
        assertFailsWith<ValidationException> { GooglePlayNotifications.parse("""{"message":{}}""", "") }
    }
}
