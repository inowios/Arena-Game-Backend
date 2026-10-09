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

    private suspend fun ApplicationTestBuilder.call(method: HttpMethod, path: String, token: String? = null, body: String? = null, expect: Int = 200): JsonElement {
        val resp = client.request("/api/v1$path") {
            this.method = method
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
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
}
