package com.matchdayclub.core.store

import com.matchdayclub.domain.exception.UpstreamException
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.usecase.economy.ReceiptVerifier
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
internal fun sha256Hex(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
private fun pemBody(pem: String) = pem.replace(Regex("-----(BEGIN|END) [A-Z ]+-----"), "").replace("\\n", "").replace(Regex("\\s"), "")

/** OAuth2 access tokens for a Google service account (JWT bearer grant), cached until shortly before expiry. */
class GoogleServiceAccount(private val http: HttpGateway, serviceAccountJson: String, private val scope: String, private val now: () -> Instant = Instant::now) {
    private val json = Json.parseToJsonElement(serviceAccountJson).jsonObject
    private val email = json["client_email"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("service account: client_email missing")
    private val tokenUri = json["token_uri"]?.jsonPrimitive?.contentOrNull ?: "https://oauth2.googleapis.com/token"
    private val key: PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pemBody(json["private_key"]!!.jsonPrimitive.content))))
    @Volatile private var cached: Pair<Instant, String>? = null

    suspend fun accessToken(): String {
        val t = now()
        cached?.let { if (t.isBefore(it.first.minusSeconds(60))) return it.second }
        val header = b64url("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = b64url(buildJsonObject {
            put("iss", email); put("scope", scope); put("aud", tokenUri); put("iat", t.epochSecond); put("exp", t.epochSecond + 3600)
        }.toString().toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(key); update("$header.$claims".toByteArray()) }.sign()
        val reply = http.send("POST", tokenUri, mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            "grant_type=${enc("urn:ietf:params:oauth:grant-type:jwt-bearer")}&assertion=$header.$claims.${b64url(sig)}")
        if (reply.status !in 200..299) throw UpstreamException("Google auth failed (${reply.status})")
        val o = Json.parseToJsonElement(reply.body).jsonObject
        val token = o["access_token"]!!.jsonPrimitive.content
        cached = t.plusSeconds(o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600) to token
        return token
    }
}

/**
 * Google Play one-time products: `purchases.products.get`. Only PURCHASED (purchaseState 0) is granted; the order id
 * is the idempotency key. The app consumes/acknowledges through its billing library after the server granted.
 */
class GooglePlayVerifier(private val http: HttpGateway, private val account: GoogleServiceAccount, private val packageName: String) : ReceiptVerifier {
    override suspend fun verify(productId: String, token: String): String {
        if (token.isBlank()) throw ValidationException("Invalid receipt")
        val url = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${enc(packageName)}/purchases/products/${enc(productId)}/tokens/${enc(token)}"
        val reply = http.send("GET", url, mapOf("Authorization" to "Bearer ${account.accessToken()}"), null)
        when {
            reply.status in setOf(400, 404, 410) -> throw ValidationException("Invalid receipt")
            reply.status !in 200..299 -> throw UpstreamException("Google Play verification failed (${reply.status})")
        }
        val o = Json.parseToJsonElement(reply.body).jsonObject
        when (o["purchaseState"]?.jsonPrimitive?.intOrNull ?: 0) {
            0 -> {}
            2 -> throw ValidationException("Purchase is pending")
            else -> throw ValidationException("Purchase was cancelled")
        }
        val orderId = o["orderId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        return "google:" + (orderId ?: sha256Hex(token))
    }
}

/**
 * App Store Server API: looks the transaction up by id (production first, then sandbox for TestFlight/sandbox
 * purchases), checks bundle, product and revocation. The signed transaction comes straight from Apple over TLS;
 * when [roots] (Apple Root CA - G3, from apple.com/certificateauthority) are configured its x5c chain and ES256
 * signature are verified as well.
 */
class AppStoreVerifier(
    private val http: HttpGateway,
    private val issuerId: String,
    private val keyId: String,
    privateKeyPem: String,
    private val bundleId: String,
    private val roots: List<X509Certificate> = emptyList(),
    private val now: () -> Instant = Instant::now,
    private val hosts: List<String> = listOf("https://api.storekit.itunes.apple.com", "https://api.storekit-sandbox.itunes.apple.com"),
) : ReceiptVerifier {
    private val log = LoggerFactory.getLogger("AppStoreVerifier")
    private val key: PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pemBody(privateKeyPem))))

    /** ES256 bearer token for the App Store Server API (valid 20 minutes). */
    fun bearer(): String {
        val t = now().epochSecond
        val header = b64url(buildJsonObject { put("alg", "ES256"); put("kid", keyId); put("typ", "JWT") }.toString().toByteArray())
        val claims = b64url(buildJsonObject { put("iss", issuerId); put("iat", t); put("exp", t + 1200); put("aud", "appstoreconnect-v1"); put("bid", bundleId) }.toString().toByteArray())
        val sig = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(key); update("$header.$claims".toByteArray()) }.sign()
        return "$header.$claims.${b64url(sig)}"
    }

    override suspend fun verify(productId: String, token: String): String {
        val transactionId = token.trim()
        if (!transactionId.matches(Regex("^[0-9]{1,32}$"))) throw ValidationException("Invalid receipt")
        for (host in hosts) {
            val reply = http.send("GET", "$host/inApps/v1/transactions/$transactionId", mapOf("Authorization" to "Bearer ${bearer()}"), null)
            if (reply.status == 404) continue
            if (reply.status == 401) throw UpstreamException("App Store authentication failed")
            if (reply.status !in 200..299) throw UpstreamException("App Store verification failed (${reply.status})")
            val jws = Json.parseToJsonElement(reply.body).jsonObject["signedTransactionInfo"]?.jsonPrimitive?.content ?: throw UpstreamException("App Store reply without transaction")
            val tx = Jws.payload(jws, roots)
            if (tx["bundleId"]?.jsonPrimitive?.contentOrNull != bundleId) throw ValidationException("Receipt is for another app")
            if (tx["productId"]?.jsonPrimitive?.contentOrNull != productId) throw ValidationException("Receipt is for another product")
            if (tx["revocationDate"] != null) throw ValidationException("Purchase was refunded")
            val id = tx["transactionId"]?.jsonPrimitive?.contentOrNull ?: transactionId
            log.info("App Store transaction $id ($productId) verified on $host")
            return "apple:$id"
        }
        throw ValidationException("Invalid receipt")
    }
}

/** Compact JWS (ES256 with an x5c chain, as Apple signs transactions). */
object Jws {
    fun payload(jws: String, roots: List<X509Certificate>): JsonObject {
        val parts = jws.split('.')
        if (parts.size != 3) throw ValidationException("Malformed signed data")
        val dec = Base64.getUrlDecoder()
        if (roots.isNotEmpty()) {
            val header = Json.parseToJsonElement(String(dec.decode(parts[0]))).jsonObject
            if (header["alg"]?.jsonPrimitive?.contentOrNull != "ES256") throw ValidationException("Unexpected signature algorithm")
            val cf = CertificateFactory.getInstance("X.509")
            val chain = header["x5c"]?.jsonArray?.map { cf.generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(it.jsonPrimitive.content))) as X509Certificate }
                ?: throw ValidationException("Signed data without certificate chain")
            val path = chain.filterNot { c -> roots.any { it == c } }
            if (path.isEmpty()) throw ValidationException("Empty certificate chain")
            try {
                val params = PKIXParameters(roots.map { TrustAnchor(it, null) }.toSet()).apply { isRevocationEnabled = false }
                CertPathValidator.getInstance("PKIX").validate(cf.generateCertPath(path), params)
            } catch (e: Exception) { throw ValidationException("Untrusted certificate chain") }
            val ok = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initVerify(path.first().publicKey); update("${parts[0]}.${parts[1]}".toByteArray()) }
                .verify(dec.decode(parts[2]))
            if (!ok) throw ValidationException("Bad signature")
        }
        return Json.parseToJsonElement(String(dec.decode(parts[1]))).jsonObject
    }
}

/**
 * AdMob rewarded-ad server-side verification: the callback's query string up to "&signature=" is signed (ECDSA
 * SHA-256, DER) with one of Google's published keys (key_id → key, refreshed daily or on an unknown id).
 */
class AdMobSsvVerifier(
    private val http: HttpGateway,
    private val keysUrl: String = "https://www.gstatic.com/admob/reward/verifier-keys.json",
    private val now: () -> Instant = Instant::now,
) {
    @Volatile private var cache: Pair<Instant, Map<Long, java.security.PublicKey>>? = null

    private suspend fun keys(force: Boolean): Map<Long, java.security.PublicKey> {
        val c = cache
        if (!force && c != null && now().isBefore(c.first.plusSeconds(86_400))) return c.second
        val reply = http.send("GET", keysUrl, emptyMap(), null)
        if (reply.status !in 200..299) throw UpstreamException("AdMob keys unavailable (${reply.status})")
        val kf = KeyFactory.getInstance("EC")
        val map = Json.parseToJsonElement(reply.body).jsonObject["keys"]!!.jsonArray.associate { k ->
            val o = k.jsonObject
            val der = o["base64"]?.jsonPrimitive?.contentOrNull ?: pemBody(o["pem"]!!.jsonPrimitive.content)
            o["keyId"]!!.jsonPrimitive.long to kf.generatePublic(java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(der)))
        }
        cache = now() to map
        return map
    }

    /** Verifies [rawQuery] (the callback's query string, without '?') and returns its decoded parameters. */
    suspend fun verify(rawQuery: String): Map<String, String> {
        val cut = rawQuery.indexOf("&signature=")
        if (cut < 0) throw ValidationException("Missing signature")
        val params = rawQuery.split('&').filter { it.isNotEmpty() }.associate { p ->
            val i = p.indexOf('=')
            if (i < 0) java.net.URLDecoder.decode(p, Charsets.UTF_8) to "" else java.net.URLDecoder.decode(p.substring(0, i), Charsets.UTF_8) to java.net.URLDecoder.decode(p.substring(i + 1), Charsets.UTF_8)
        }
        val keyId = params["key_id"]?.toLongOrNull() ?: throw ValidationException("Missing key_id")
        val signature = runCatching { Base64.getUrlDecoder().decode(params["signature"]!!.trimEnd('=')) }.getOrElse { throw ValidationException("Bad signature encoding") }
        val key = keys(false)[keyId] ?: keys(true)[keyId] ?: throw ValidationException("Unknown key_id")
        val ok = Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update(rawQuery.substring(0, cut).toByteArray(Charsets.UTF_8)) }.verify(signature)
        if (!ok) throw ValidationException("Bad signature")
        return params
    }
}
