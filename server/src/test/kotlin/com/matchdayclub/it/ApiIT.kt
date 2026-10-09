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
