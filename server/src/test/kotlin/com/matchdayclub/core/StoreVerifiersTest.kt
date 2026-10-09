package com.matchdayclub.core

import com.matchdayclub.core.store.*
import com.matchdayclub.domain.exception.UpstreamException
import com.matchdayclub.domain.exception.ValidationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import kotlin.test.*

class StoreVerifiersTest {
    private val now = Instant.parse("2026-10-09T10:00:00Z")
    private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun pem(kind: String, der: ByteArray) = "-----BEGIN $kind-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $kind-----\n"
    private fun ecPair() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** Records calls and answers from a handler. */
    private class FakeHttp(val handler: (String, String, Map<String, String>, String?) -> HttpReply) : HttpGateway {
        val calls = mutableListOf<String>()
        override suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpReply { calls += "$method $url"; return handler(method, url, headers, body) }
    }

    // ---------------- AdMob SSV ----------------

    private val adKeys = ecPair()
    private fun adHttp(keyId: Long = 42) = FakeHttp { _, url, _, _ ->
        assertTrue(url.contains("verifier-keys"))
        HttpReply(200, buildJsonObject { putJsonArray("keys") { addJsonObject { put("keyId", keyId); put("base64", Base64.getEncoder().encodeToString(adKeys.public.encoded)) } } }.toString())
    }
    private fun signedQuery(content: String, keyId: Long = 42): String {
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(adKeys.private); update(content.toByteArray()) }.sign()
        return "$content&signature=${b64url(sig)}&key_id=$keyId"
    }

    @Test
    fun `admob callback signature is verified and parameters decoded`() = runBlocking {
        val v = AdMobSsvVerifier(adHttp(), now = { now })
        val content = "ad_network=5450213213286189855&ad_unit=1234567890&custom_data=FREE_SPEEDUP%3Aabc&reward_amount=1&reward_item=Reward&timestamp=1507770365237823&transaction_id=18fa792de1bca816048293fc71035638&user_id=u-1"
        val params = v.verify(signedQuery(content))
        assertEquals("FREE_SPEEDUP:abc", params["custom_data"]); assertEquals("u-1", params["user_id"]); assertEquals("18fa792de1bca816048293fc71035638", params["transaction_id"])
        assertFailsWith<ValidationException> { v.verify(signedQuery(content).replace("user_id=u-1", "user_id=u-2")) }
        assertFailsWith<ValidationException> { v.verify(content) }
        Unit
    }

    @Test
    fun `admob keys are refreshed once for an unknown key id`() = runBlocking {
        val http = adHttp(keyId = 42)
        val v = AdMobSsvVerifier(http, now = { now })
        v.verify(signedQuery("transaction_id=1&user_id=a"))
        assertEquals(1, http.calls.size)
        assertFailsWith<ValidationException> { v.verify(signedQuery("transaction_id=2&user_id=a", keyId = 7)) }
        assertEquals(2, http.calls.size)
        Unit
    }

    // ---------------- Google Play ----------------

    private val serviceAccount: String = run {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        buildJsonObject { put("client_email", "verifier@test.iam.gserviceaccount.com"); put("private_key", pem("PRIVATE KEY", kp.private.encoded)); put("token_uri", "https://oauth2.googleapis.com/token") }.toString()
    }

    private fun play(purchase: (String) -> HttpReply): Pair<GooglePlayVerifier, FakeHttp> {
        val http = FakeHttp { method, url, headers, body ->
            if (url.endsWith("/token")) {
                assertEquals("POST", method); assertTrue(body!!.startsWith("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="))
                HttpReply(200, """{"access_token":"ya29.test","expires_in":3599,"token_type":"Bearer"}""")
            } else { assertEquals("Bearer ya29.test", headers["Authorization"]); purchase(url) }
        }
        return GooglePlayVerifier(http, GoogleServiceAccount(http, serviceAccount, "https://www.googleapis.com/auth/androidpublisher", now = { now }), "com.inowios.matchdayclub") to http
    }

    @Test
    fun `google play purchase is granted by order id`() = runBlocking {
        val (v, http) = play { url ->
            assertTrue(url.contains("/applications/com.inowios.matchdayclub/purchases/products/gold_small/tokens/tok-1"))
            HttpReply(200, """{"purchaseState":0,"consumptionState":0,"orderId":"GPA.1234-5678","acknowledgementState":0,"purchaseType":0}""")
        }
        assertEquals("google:GPA.1234-5678", v.verify("gold_small", "tok-1"))
        v.verify("gold_small", "tok-1")
        assertEquals(1, http.calls.count { it.endsWith("/token") }, "access token is cached")
    }

    @Test
    fun `google play rejects pending, cancelled and unknown purchases`() = runBlocking {
        assertFailsWith<ValidationException> { play { HttpReply(200, """{"purchaseState":2,"orderId":"x"}""") }.first.verify("gold_small", "t") }
        assertFailsWith<ValidationException> { play { HttpReply(200, """{"purchaseState":1,"orderId":"x"}""") }.first.verify("gold_small", "t") }
        assertFailsWith<ValidationException> { play { HttpReply(404, """{"error":{"code":404}}""") }.first.verify("gold_small", "t") }
        assertFailsWith<UpstreamException> { play { HttpReply(503, "") }.first.verify("gold_small", "t") }
        Unit
    }

    // ---------------- App Store ----------------

    private val apiKey = ecPair()

    private fun jws(payload: JsonObject, key: PrivateKey, x5c: List<X509Certificate> = emptyList()): String {
        val header = buildJsonObject { put("alg", "ES256"); if (x5c.isNotEmpty()) putJsonArray("x5c") { x5c.forEach { add(Base64.getEncoder().encodeToString(it.encoded)) } } }
        val signingInput = b64url(header.toString().toByteArray()) + "." + b64url(payload.toString().toByteArray())
        val sig = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initSign(key); update(signingInput.toByteArray()) }.sign()
        return "$signingInput.${b64url(sig)}"
    }

    private fun transaction(product: String = "gold_small", bundle: String = "com.inowios.matchdayclub", revoked: Boolean = false) = buildJsonObject {
        put("transactionId", "2000000123456789"); put("originalTransactionId", "2000000123456789"); put("bundleId", bundle); put("productId", product)
        put("purchaseDate", 1760000000000); put("type", "Consumable"); put("environment", "Sandbox"); if (revoked) put("revocationDate", 1760000100000)
    }

    private fun appStore(signed: String, roots: List<X509Certificate> = emptyList(), productionHas: Boolean = false): Pair<AppStoreVerifier, FakeHttp> {
        val http = FakeHttp { _, url, headers, _ ->
            val bearer = headers["Authorization"]!!.removePrefix("Bearer ")
            val parts = bearer.split('.')
            val header = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[0]))).jsonObject
            assertEquals("ES256", header["alg"]!!.jsonPrimitive.content); assertEquals("KEY123", header["kid"]!!.jsonPrimitive.content)
            val ok = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initVerify(apiKey.public); update("${parts[0]}.${parts[1]}".toByteArray()) }.verify(Base64.getUrlDecoder().decode(parts[2]))
            assertTrue(ok, "bearer signed with the configured key")
            val claims = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]))).jsonObject
            assertEquals("appstoreconnect-v1", claims["aud"]!!.jsonPrimitive.content); assertEquals("com.inowios.matchdayclub", claims["bid"]!!.jsonPrimitive.content)
            if (url.contains("sandbox") || productionHas) HttpReply(200, buildJsonObject { put("signedTransactionInfo", signed) }.toString())
            else HttpReply(404, """{"errorCode":4040010,"errorMessage":"Transaction id not found."}""")
        }
        return AppStoreVerifier(http, "issuer-1", "KEY123", pem("PRIVATE KEY", apiKey.private.encoded), "com.inowios.matchdayclub", roots, now = { now }) to http
    }

    @Test
    fun `app store transaction is looked up (sandbox fallback) and checked`() = runBlocking {
        val signer = ecPair()
        val (v, http) = appStore(jws(transaction(), signer.private))
        assertEquals("apple:2000000123456789", v.verify("gold_small", "2000000123456789"))
        assertEquals(2, http.calls.size); assertTrue(http.calls[1].contains("storekit-sandbox"))
        assertFailsWith<ValidationException> { appStore(jws(transaction(product = "gold_large"), signer.private)).first.verify("gold_small", "2000000123456789") }
        assertFailsWith<ValidationException> { appStore(jws(transaction(bundle = "com.other.app"), signer.private)).first.verify("gold_small", "2000000123456789") }
        assertFailsWith<ValidationException> { appStore(jws(transaction(revoked = true), signer.private)).first.verify("gold_small", "2000000123456789") }
        assertFailsWith<ValidationException> { v.verify("gold_small", "not-a-transaction") }
        Unit
    }

    @Test
    fun `signed transaction chain is verified against the configured root`() = runBlocking {
        val keytool = File(System.getProperty("java.home"), "bin/keytool")
        assumeTrue(keytool.canExecute(), "keytool not available")
        val dir = kotlin.io.path.createTempDirectory("aasa").toFile()
        val ks = File(dir, "ks.p12").path
        fun kt(vararg args: String) {
            val p = ProcessBuilder(listOf(keytool.path) + args.toList()).directory(dir).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(0, p.waitFor(), out)
        }
        val common = arrayOf("-keystore", ks, "-storetype", "PKCS12", "-storepass", "changeit")
        kt("-genkeypair", "-alias", "root", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=Test Root CA", "-ext", "bc:c", "-validity", "3650", *common)
        kt("-genkeypair", "-alias", "leaf", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=Test Leaf", "-validity", "3650", *common)
        kt("-certreq", "-alias", "leaf", "-file", "leaf.csr", *common)
        kt("-gencert", "-alias", "root", "-infile", "leaf.csr", "-outfile", "leaf.cer", "-validity", "3650", *common)
        val store = KeyStore.getInstance("PKCS12").apply { File(ks).inputStream().use { load(it, "changeit".toCharArray()) } }
        val root = store.getCertificate("root") as X509Certificate
        val leafKey = store.getKey("leaf", "changeit".toCharArray()) as PrivateKey
        val leaf = File(dir, "leaf.cer").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

        val good = jws(transaction(), leafKey, listOf(leaf, root))
        assertEquals("apple:2000000123456789", appStore(good, roots = listOf(root)).first.verify("gold_small", "2000000123456789"))
        // signed by a key that isn't the leaf's
        assertFailsWith<ValidationException> { appStore(jws(transaction(), ecPair().private, listOf(leaf, root)), roots = listOf(root)).first.verify("gold_small", "2000000123456789") }
        // a chain that doesn't lead to the trusted root
        val otherRoot = run {
            kt("-genkeypair", "-alias", "other", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=Other Root", "-ext", "bc:c", "-validity", "3650", *common)
            KeyStore.getInstance("PKCS12").apply { File(ks).inputStream().use { load(it, "changeit".toCharArray()) } }.getCertificate("other") as X509Certificate
        }
        assertFailsWith<ValidationException> { appStore(good, roots = listOf(otherRoot)).first.verify("gold_small", "2000000123456789") }
        // without x5c when roots are configured
        assertFailsWith<ValidationException> { appStore(jws(transaction(), leafKey), roots = listOf(root)).first.verify("gold_small", "2000000123456789") }
        dir.deleteRecursively()
        Unit
    }
}
