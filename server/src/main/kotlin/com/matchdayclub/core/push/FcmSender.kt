package com.matchdayclub.core.push

import com.matchdayclub.core.metrics.Metrics
import com.matchdayclub.domain.repository.PushTokenRepository
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID

/** FCM HTTP v1 with a service-account JWT (no Firebase SDK). No-op when not configured. */
class FcmSender(private val client: HttpClient, private val tokens: PushTokenRepository, serviceAccountJson: String?, private val projectIdOverride: String?, private val users: com.matchdayclub.domain.repository.UserRepository? = null) {
    private val log = LoggerFactory.getLogger("FcmSender")
    private val account: JsonObject? = serviceAccountJson?.takeIf { it.isNotBlank() }?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
    private val projectId get() = projectIdOverride?.takeIf { it.isNotBlank() } ?: account?.get("project_id")?.jsonPrimitive?.content
    @Volatile private var cached: Pair<Long, String>? = null
    val enabled get() = account != null && projectId != null

    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private suspend fun accessToken(): String {
        cached?.let { if (System.currentTimeMillis() < it.first - 60_000) return it.second }
        val acc = account!!
        val now = System.currentTimeMillis() / 1000
        val header = b64("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = b64("""{"iss":"${acc["client_email"]!!.jsonPrimitive.content}","scope":"https://www.googleapis.com/auth/firebase.messaging","aud":"https://oauth2.googleapis.com/token","iat":$now,"exp":${now + 3600}}""".toByteArray())
        val pem = acc["private_key"]!!.jsonPrimitive.content.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace("\\s".toRegex(), "")
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key); update("$header.$claims".toByteArray()) }.sign()
        val assertion = "$header.$claims.${b64(sig)}"
        val resp = client.post("https://oauth2.googleapis.com/token") { contentType(ContentType.Application.FormUrlEncoded); setBody("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=$assertion") }
        val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        val token = json["access_token"]!!.jsonPrimitive.content
        cached = (System.currentTimeMillis() + json["expires_in"]!!.jsonPrimitive.long * 1000) to token
        return token
    }

    /** Sends each user the text in their language ([text] gets "en", "ru"…; users without a stored locale get English). */
    suspend fun sendLocalized(userIds: Collection<UUID>, data: Map<String, String> = emptyMap(), text: (String) -> Pair<String, String>) {
        if (userIds.isEmpty()) return
        val locales = runCatching { users?.locales(userIds) }.getOrNull() ?: emptyMap()
        for ((lang, ids) in userIds.groupBy { com.matchdayclub.common.i18n.ServerI18n.normalize(locales[it]) }) {
            val (title, body) = text(lang)
            sendToUsers(ids, title, body, data)
        }
    }

    suspend fun sendToUsers(userIds: Collection<UUID>, title: String, body: String, data: Map<String, String> = emptyMap()) {
        val list = tokens.tokensForUsers(userIds)
        if (list.isEmpty()) return
        if (!enabled) { log.info("FCM not configured; would send '$title' to ${list.size} tokens"); return }
        val access = runCatching { accessToken() }.getOrElse { log.warn("FCM token failed", it); return }
        for (t in list) {
            val msg = buildJsonObject {
                putJsonObject("message") {
                    put("token", t.token)
                    putJsonObject("notification") { put("title", title); put("body", body) }
                    putJsonObject("data") { data.forEach { (k, v) -> put(k, v) } }
                }
            }
            val resp = runCatching { client.post("https://fcm.googleapis.com/v1/projects/$projectId/messages:send") { header("Authorization", "Bearer $access"); contentType(ContentType.Application.Json); setBody(msg.toString()) } }.getOrNull()
            if (resp == null) { Metrics.inc("push_total", "result" to "error"); continue }
            if (resp.status.isSuccess()) Metrics.inc("push_total", "result" to "sent")
            else if (resp.status == HttpStatusCode.NotFound || resp.bodyAsText().contains("UNREGISTERED")) { tokens.delete(t.token); Metrics.inc("push_total", "result" to "unregistered") }
            else { log.warn("FCM send failed ${resp.status}: ${resp.bodyAsText().take(200)}"); Metrics.inc("push_total", "result" to "failed") }
        }
    }
}
