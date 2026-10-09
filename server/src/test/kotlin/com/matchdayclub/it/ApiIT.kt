package com.matchdayclub.it

import com.matchdayclub.module
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Route-level tests against the real application module and PostgreSQL (mirrors scripts/smoke.sh). */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ApiIT {
    @BeforeEach
    fun setUp() { TestDb.fresh().close() }

    private fun JsonElement.o() = jsonObject
    private val JsonElement.data get() = jsonObject["data"]!!

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, token: String? = null, body: String? = null, expect: Int = 200, lang: String? = null): JsonElement {
        val resp = client.request("/api/v1$path") {
            this.method = method
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            lang?.let { header(HttpHeaders.AcceptLanguage, it) }
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        val text = resp.bodyAsText()
        assertEquals(expect, resp.status.value, "$method $path -> $text")
        return Json.parseToJsonElement(text)
    }

    private suspend fun ApplicationTestBuilder.guestWithClub(device: String, name: String): String {
        val token = call(HttpMethod.Post, "/auth/guest", body = """{"deviceId":"$device"}""").data.o()["accessToken"]!!.jsonPrimitive.content
        call(HttpMethod.Post, "/club", token, """{"name":"$name","primaryColor":"#112233","secondaryColor":"#ffffff","timezone":"UTC"}""", expect = 201)
        return token
    }

    @Test
    fun `club, buildings, builders and squad over HTTP`() = testApplication {
        environment { config = TestDb.appConfig() }
        application { module() }
        val t = guestWithClub("it-device-0001", "Api FC")
        val club = call(HttpMethod.Get, "/club", t).data.o()
        assertEquals(2500, club["club"]!!.o()["coins"]!!.jsonPrimitive.int)
        assertEquals(1, club["club"]!!.o()["builderSlots"]!!.jsonPrimitive.int)
        val academy = call(HttpMethod.Post, "/club/buildings", t, """{"type":"ACADEMY","x":0,"y":10}""", expect = 201).data.o()["id"]!!.jsonPrimitive.content
        // one builder: a second construction must wait
        call(HttpMethod.Post, "/club/buildings", t, """{"type":"TRAINING_CENTER","x":3,"y":10}""", expect = 409)
        call(HttpMethod.Post, "/club/builders", t, expect = 402)   // 250 gold needed, 50 owned
        call(HttpMethod.Post, "/club/buildings/$academy/speedup", t)
        call(HttpMethod.Post, "/club/buildings", t, """{"type":"TRAINING_CENTER","x":3,"y":10}""", expect = 201)
        call(HttpMethod.Post, "/club/buildings/$academy/move", t, """{"x":0,"y":8,"rotation":0}""")
        val graduate = call(HttpMethod.Post, "/club/academy/claim", t, expect = 201).data.o()
        assertTrue(graduate["overall"]!!.jsonPrimitive.int > 0)
        call(HttpMethod.Post, "/club/academy/claim", t, expect = 409)
        val squad = call(HttpMethod.Get, "/club/athletes", t).data.o()
        assertEquals(15, squad["athletes"]!!.jsonArray.size)
        val lineup = call(HttpMethod.Get, "/club/lineup/football", t).data.o()["lineup"]!!.o()
        assertEquals(11, lineup["starters"]!!.jsonArray.size)
    }

    @Test
    fun `chat, challenges, invite landing pages and app links over HTTP`() = testApplication {
        environment { config = TestDb.appConfig(mapOf("links.baseUrl" to "https://mdc.test", "links.androidCertSha256" to "AA:BB", "links.iosTeamId" to "TEAM1")) }
        application { module() }
        val a = guestWithClub("it-device-0101", "Alpha FC")
        val b = guestWithClub("it-device-0102", "Bravo FC")
        val league = call(HttpMethod.Post, "/leagues/private", a, """{"name":"Office <b>"}""", expect = 201).data.o()
        val code = league["code"]!!.jsonPrimitive.content; val leagueId = league["id"]!!.jsonPrimitive.content
        call(HttpMethod.Post, "/leagues/private/join", b, """{"code":"$code"}""")
        call(HttpMethod.Post, "/leagues/$leagueId/messages", b, """{"text":"good luck"}""", expect = 201)
        val lines = call(HttpMethod.Get, "/leagues/$leagueId/messages", a).data.jsonArray
        assertEquals(listOf("SYSTEM", "USER"), lines.map { it.o()["kind"]!!.jsonPrimitive.content })
        val challenge = call(HttpMethod.Post, "/challenges", a, expect = 201).data.o()
        val cc = challenge["code"]!!.jsonPrimitive.content
        assertEquals("https://mdc.test/c/$cc", challenge["link"]!!.jsonPrimitive.content)
        val m = call(HttpMethod.Post, "/challenges/$cc/accept", b, expect = 201).data.o()
        assertEquals("CHALLENGE", m["fixture"]!!.o()["kind"]!!.jsonPrimitive.content)
        call(HttpMethod.Post, "/challenges/$cc/accept", b, expect = 409)
        val clubA = call(HttpMethod.Get, "/club", a).data.o()["club"]!!.o()["id"]!!.jsonPrimitive.content
        assertEquals(1, call(HttpMethod.Get, "/clubs/$clubA/h2h", b).data.o()["fixtures"]!!.jsonArray.size)
        assertTrue(call(HttpMethod.Get, "/clubs/$clubA/grounds", b).data.o()["buildings"]!!.jsonArray.size >= 2)
        val landing = client.get("/l/$code") { header(HttpHeaders.AcceptLanguage, "ru-RU,ru;q=0.9") }
        val html = landing.bodyAsText()
        assertEquals(200, landing.status.value); assertTrue(html.contains(code) && html.contains("matchdayclub://l/$code") && html.contains("приглашает"))
        assertTrue(html.contains("Office &lt;b&gt;"), "club and league names are escaped")
        assertEquals(404, client.get("/l/NOPE99").status.value)
        assertTrue(client.get("/c/$cc").bodyAsText().contains("Alpha FC"))
        assertTrue(client.get("/.well-known/assetlinks.json").bodyAsText().contains("\"AA:BB\""))
        assertTrue(client.get("/.well-known/apple-app-site-association").bodyAsText().contains("TEAM1.com.inowios.matchdayclub"))
        assertEquals("https://mdc.test", Json.parseToJsonElement(client.get("/api/v1/config").bodyAsText()).data.o()["publicBaseUrl"]!!.jsonPrimitive.content)
        // inbox: the challenger learns about the accepted challenge, the creator about the new member
        val inboxA = call(HttpMethod.Get, "/club/inbox", a).data.o()
        val kinds = inboxA["items"]!!.jsonArray.map { it.o()["kind"]!!.jsonPrimitive.content }
        assertTrue("CHALLENGE" in kinds && "LEAGUE_JOIN" in kinds, kinds.toString())
        assertEquals("Bravo FC", inboxA["items"]!!.jsonArray.first { it.o()["kind"]!!.jsonPrimitive.content == "CHALLENGE" }.o()["data"]!!.o()["club"]!!.jsonPrimitive.content)
        assertEquals(2, call(HttpMethod.Get, "/club", a).data.o()["inboxUnread"]!!.jsonPrimitive.int)
        call(HttpMethod.Post, "/club/inbox/read", a)
        assertEquals(0, call(HttpMethod.Get, "/club", a).data.o()["inboxUnread"]!!.jsonPrimitive.int)
    }

    @Test
    fun `device transfer and account deletion over HTTP`() = testApplication {
        environment { config = TestDb.appConfig() }
        application { module() }
        val old = guestWithClub("it-device-0301", "Mover FC")
        val code = call(HttpMethod.Post, "/auth/transfer-code", old).data.o()["code"]!!.jsonPrimitive.content
        assertTrue(Regex("^[A-Z2-9]{4}-[A-Z2-9]{4}$").matches(code), code)
        val moved = call(HttpMethod.Post, "/auth/transfer", body = """{"code":"$code","deviceId":"it-device-0302"}""").data.o()["accessToken"]!!.jsonPrimitive.content
        assertEquals("Mover FC", call(HttpMethod.Get, "/club", moved).data.o()["club"]!!.o()["name"]!!.jsonPrimitive.content)
        call(HttpMethod.Post, "/auth/transfer", body = """{"code":"$code","deviceId":"it-device-0303"}""", expect = 409)
        // a guest login from the new device opens the moved account
        val again = call(HttpMethod.Post, "/auth/guest", body = """{"deviceId":"it-device-0302"}""").data.o()
        assertEquals(false, again["isNewUser"]!!.jsonPrimitive.boolean)
        call(HttpMethod.Delete, "/me", moved)
        call(HttpMethod.Get, "/club", moved, expect = 404)
        val fresh = call(HttpMethod.Post, "/auth/guest", body = """{"deviceId":"it-device-0302"}""").data.o()
        assertEquals(true, fresh["isNewUser"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `leagues, friendlies and economy over HTTP`() = testApplication {
        environment { config = TestDb.appConfig() }
        application { module() }
        val a = guestWithClub("it-device-0002", "Alpha FC")
        val b = guestWithClub("it-device-0003", "Bravo FC")
        val mine = call(HttpMethod.Get, "/leagues/mine", a).data.o()
        assertEquals(8, mine["public"]!!.o()["table"]!!.jsonArray.size)
        val friendly = call(HttpMethod.Post, "/friendlies", a, "{}", expect = 201).data.o()
        assertEquals("PLAYED", friendly["fixture"]!!.o()["status"]!!.jsonPrimitive.content)
        val code = call(HttpMethod.Post, "/leagues/private", a, """{"name":"Office"}""", expect = 201).data.o()["code"]!!.jsonPrimitive.content
        assertEquals(2, call(HttpMethod.Post, "/leagues/private/join", b, """{"code":"$code"}""").data.o()["memberCount"]!!.jsonPrimitive.int)
        call(HttpMethod.Post, "/leagues/private/join", b, """{"code":"$code"}""", expect = 409)
        assertEquals(1, call(HttpMethod.Post, "/club/daily/claim", a).data.o()["day"]!!.jsonPrimitive.int)
        call(HttpMethod.Post, "/club/daily/claim", a, expect = 409)
        val bought = call(HttpMethod.Post, "/purchases/verify", a, """{"store":"DEV","productId":"gold_medium","token":"dev:it-1"}""").data.o()
        assertEquals("GRANTED", bought["status"]!!.jsonPrimitive.content)
        assertEquals("DUPLICATE", call(HttpMethod.Post, "/purchases/verify", a, """{"store":"DEV","productId":"gold_medium","token":"dev:it-1"}""").data.o()["status"]!!.jsonPrimitive.content)
        assertEquals(2, call(HttpMethod.Post, "/club/builders", a).data.o()["builderSlots"]!!.jsonPrimitive.int)
        call(HttpMethod.Post, "/club/builders", a, expect = 409)
    }

    @Test
    fun `cosmetics, coin packs, achievements and ad rewards over HTTP`() = testApplication {
        environment { config = TestDb.appConfig(mapOf("ads.clientRewards" to "false")) }
        application { module() }
        val t = guestWithClub("it-device-0201", "Cosmo FC")
        assertTrue(call(HttpMethod.Get, "/catalog/cosmetics").data.jsonArray.any { it.o()["id"]!!.jsonPrimitive.content == "kit_stripes" })
        val poor = call(HttpMethod.Post, "/club/cosmetics/kit_stripes/buy", t, expect = 402, lang = "ru-RU,ru;q=0.9")   // 60 gold, 50 owned
        assertEquals("Недостаточно золота", poor.o()["errors"]!!.jsonArray[0].jsonPrimitive.content)
        val me = call(HttpMethod.Patch, "/me", t, """{"locale":"uk-UA"}""").data.o()
        assertEquals("uk", me["user"]!!.o()["locale"]!!.jsonPrimitive.content)
        call(HttpMethod.Post, "/purchases/verify", t, """{"store":"DEV","productId":"gold_small","token":"dev:it-cos"}""")
        val inv = call(HttpMethod.Post, "/club/cosmetics/kit_stripes/buy", t).data.o()
        assertTrue(inv["owned"]!!.jsonArray.any { it.jsonPrimitive.content == "kit_stripes" })
        assertEquals(90, inv["club"]!!.o()["gold"]!!.jsonPrimitive.int)
        val eq = call(HttpMethod.Post, "/club/cosmetics/equip", t, """{"kind":"KIT","itemId":"kit_stripes"}""").data.o()
        assertEquals("kit_stripes", eq["equipped"]!!.o()["kit"]!!.jsonPrimitive.content)
        call(HttpMethod.Post, "/club/cosmetics/equip", t, """{"kind":"FRAME","itemId":"frame_gold"}""", expect = 409)
        val state = call(HttpMethod.Get, "/club", t).data.o()
        assertEquals("kit_stripes", state["club"]!!.o()["kit"]!!.jsonPrimitive.content)
        assertTrue(state["ads"]!!.o()["freeSpeedupAvailable"]!!.jsonPrimitive.boolean)
        // decor looks
        call(HttpMethod.Post, "/club/buildings", t, """{"type":"DECOR","x":11,"y":11,"variant":"decor_flowerbed"}""", expect = 409)
        call(HttpMethod.Post, "/club/cosmetics/decor_flowerbed/buy", t)
        val decor = call(HttpMethod.Post, "/club/buildings", t, """{"type":"DECOR","x":11,"y":11,"variant":"decor_flowerbed"}""", expect = 201).data.o()
        assertEquals("decor_flowerbed", decor["variant"]!!.jsonPrimitive.content)
        val tree = call(HttpMethod.Post, "/club/buildings/${decor["id"]!!.jsonPrimitive.content}/variant", t, """{"itemId":"decor_tree"}""").data.o()
        assertTrue(tree["variant"] == null || tree["variant"] is JsonNull)
        // coins for gold
        val coinsBefore = call(HttpMethod.Get, "/club", t).data.o()["club"]!!.o()["coins"]!!.jsonPrimitive.long
        val afterPack = call(HttpMethod.Post, "/club/shop/coins/coins_small", t).data.o()
        assertEquals(coinsBefore + 3_000, afterPack["coins"]!!.jsonPrimitive.long); assertEquals(40, afterPack["gold"]!!.jsonPrimitive.int)
        // achievements from real counters (friendly played) and the login streak maximum
        call(HttpMethod.Post, "/friendlies", t, "{}", expect = 201)
        call(HttpMethod.Post, "/club/daily/claim", t)
        val list = call(HttpMethod.Get, "/club/achievements", t).data.jsonArray.associateBy { it.o()["id"]!!.jsonPrimitive.content }
        assertEquals(1, list.getValue("friendly_face").o()["value"]!!.jsonPrimitive.int)
        assertEquals(1, list.getValue("loyal").o()["value"]!!.jsonPrimitive.int)
        val claim = call(HttpMethod.Post, "/club/achievements/friendly_face/claim", t).data.o()
        assertEquals(5, claim["gold"]!!.jsonPrimitive.int)
        call(HttpMethod.Post, "/club/achievements/friendly_face/claim", t, expect = 409)
        // production-style ads: the client can't grant itself rewards; unsigned callbacks are refused
        call(HttpMethod.Post, "/ads/reward", t, """{"placement":"DOUBLE_INCOME"}""", expect = 403)
        assertEquals(403, client.get("/api/v1/ads/ssv?transaction_id=1&user_id=x").status.value)
        // speed-ups need something running
        call(HttpMethod.Post, "/club/medical/speedup", t, expect = 409)
    }
    @Test
    fun `admin, metrics, analytics events and legal pages over HTTP`() = testApplication {
        val token = "it-admin-token-0123456789"
        environment { config = TestDb.appConfig(mapOf("admin.token" to token, "links.supportEmail" to "help@mdc.test")) }
        application { module() }
        val t = guestWithClub("it-device-0401", "Metric FC")
        val accepted = call(HttpMethod.Post, "/analytics/events", t,
            """{"platform":"android","appVersion":"0.1.0","events":[{"name":"session_start"},{"name":"tutorial_step","props":{"step":"build"}},{"name":"Bad Name"}]}""").data.o()
        assertEquals(2, accepted["accepted"]!!.jsonPrimitive.int)
        // admin endpoints need the token
        assertEquals(401, client.get("/admin/stats").status.value)
        assertEquals(401, client.get("/metrics") { header("X-Admin-Token", "wrong-token") }.status.value)
        val metrics = client.get("/metrics") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(200, metrics.status.value)
        val text = metrics.bodyAsText()
        assertTrue(text.contains("http_requests_total{route=\"/api/v1/club\",method=\"POST\",status=\"201\"}"), text.lines().filter { it.startsWith("http_requests_total") }.joinToString("\n"))
        assertTrue(text.contains("process_uptime_seconds") && text.contains("http_request_duration_seconds_bucket"))
        suspend fun admin(method: HttpMethod, path: String, body: String? = null, expect: Int = 200): JsonElement {
            val resp = client.request(path) {
                this.method = method; header("X-Admin-Token", token)
                if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
            }
            val txt = resp.bodyAsText()
            assertEquals(expect, resp.status.value, "$method $path -> $txt")
            return Json.parseToJsonElement(txt)
        }
        val counts = admin(HttpMethod.Get, "/admin/stats").data.o()["counts"]!!.o()
        assertEquals(1, counts["clubs"]!!.jsonPrimitive.int); assertTrue(counts["bots"]!!.jsonPrimitive.int >= 7)
        assertEquals(3, counts["analyticsEvents"]!!.jsonPrimitive.int)   // club_created + 2 from the app
        val report = admin(HttpMethod.Get, "/admin/metrics?days=7").data.o()
        assertEquals(1, report["dau"]!!.jsonPrimitive.int); assertEquals(1, report["installs"]!!.jsonPrimitive.int)
        assertEquals("NO_DATA", report["kpis"]!!.o()["d1Retention"]!!.o()["status"]!!.jsonPrimitive.content)
        assertEquals(1, report["events"]!!.o()["club_created"]!!.jsonPrimitive.int)
        admin(HttpMethod.Get, "/admin/metrics?days=0", expect = 400)
        val found = admin(HttpMethod.Get, "/admin/clubs?q=metric").data.jsonArray
        assertEquals(listOf("Metric FC"), found.map { it.o()["name"]!!.jsonPrimitive.content })
        val clubId = found[0].o()["id"]!!.jsonPrimitive.content
        val card = admin(HttpMethod.Get, "/admin/clubs/$clubId").data.o()
        assertEquals("GUEST", card["user"]!!.o()["provider"]!!.jsonPrimitive.content)
        assertEquals(java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(), card["lastActive"]!!.jsonPrimitive.content)
        val granted = admin(HttpMethod.Post, "/admin/clubs/$clubId/grant", """{"coins":500,"gold":20,"reason":"Matchday outage"}""").data.o()
        assertEquals(70, granted["gold"]!!.jsonPrimitive.int)
        admin(HttpMethod.Post, "/admin/clubs/$clubId/grant", """{"gold":5,"reason":" "}""", expect = 400)
        val gift = call(HttpMethod.Get, "/club/inbox", t).data.o()["items"]!!.jsonArray.first { it.o()["kind"]!!.jsonPrimitive.content == "GIFT" }
        assertEquals("Matchday outage", gift.o()["data"]!!.o()["reason"]!!.jsonPrimitive.content)
        // weekly cup tab before any draw (the scheduler is off in tests)
        val cupView = call(HttpMethod.Get, "/cups/current", t).data.o()
        assertEquals("UPCOMING", cupView["state"]!!.jsonPrimitive.content)
        assertEquals(5, cupView["prizes"]!!.jsonArray.size); assertEquals(4, cupView["roundTimes"]!!.jsonArray.size)
        assertTrue(admin(HttpMethod.Post, "/admin/scheduler/run").data.o()["matches"]!!.jsonPrimitive.int >= 0)
        // between Thursday 12:00 and Sunday 18:00 that pass draws this active club into a cup
        val afterRun = call(HttpMethod.Get, "/cups/current", t).data.o()
        if (afterRun["state"]!!.jsonPrimitive.content != "UPCOMING") assertTrue(afterRun["ties"]!!.jsonArray.size >= 8)
        // store-required public pages
        val privacy = client.get("/privacy").bodyAsText()
        assertTrue(privacy.contains("Privacy Policy") && privacy.contains("mailto:help@mdc.test"))
        assertTrue(client.get("/delete-account?lang=ru").bodyAsText().contains("Удаление аккаунта"))
        assertTrue(client.get("/terms") { header(HttpHeaders.AcceptLanguage, "ru-RU,ru;q=0.9") }.bodyAsText().contains("Условия использования"))
        // account deletion erases the player's own events
        call(HttpMethod.Delete, "/me", t)
        assertEquals(1, admin(HttpMethod.Get, "/admin/stats").data.o()["counts"]!!.o()["analyticsEvents"]!!.jsonPrimitive.int)   // only the anonymous account_deleted
    }
}
