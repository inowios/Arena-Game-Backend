package com.arenacity.it

import com.arenacity.module
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

    private fun sql(statement: String) = java.sql.DriverManager.getConnection(TestDb.url, TestDb.user, TestDb.password).use { it.createStatement().executeUpdate(statement) }
    private fun JsonElement.strings() = jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `club, map spots and the routes that went over HTTP`() = testApplication {
        environment { config = TestDb.appConfig() }
        application { module() }
        val t = guestWithClub("it-device-0001", "Api FC")
        val club = call(HttpMethod.Get, "/club", t).data.o()
        assertEquals(2500, club["club"]!!.o()["coins"]!!.jsonPrimitive.int)
        assertEquals(1, club["stadium"]!!.jsonPrimitive.int); assertEquals(listOf("stadium"), club["spots"]!!.strings())
        assertTrue("buildings" !in club)   // the city builder's field went (V49)
        // the map's ten spots: five bought on the main island, one on each people's island
        val catalog = call(HttpMethod.Get, "/catalog/spots").data.jsonArray.map { it.o() }
        assertEquals(listOf("stadium", "academy", "hospital", "mall", "lighthouse", "marina", "android_stadium", "stone_arena", "beast_dome", "shadow_ruins"), catalog.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(3000, catalog[1]["cost"]!!.jsonPrimitive.int); assertEquals("main", catalog[1]["island"]!!.jsonPrimitive.content); assertEquals(JsonNull, catalog[1]["people"])
        assertEquals("BEAST", catalog[8]["people"]!!.jsonPrimitive.content); assertEquals("beast", catalog[8]["island"]!!.jsonPrimitive.content)
        // the builder's routes are gone
        assertEquals(404, client.get("/api/v1/catalog/buildings").status.value)
        assertEquals(404, client.get("/api/v1/catalog/land").status.value)
        assertEquals(404, client.post("/api/v1/club/buildings") { header(HttpHeaders.Authorization, "Bearer $t") }.status.value)
        // the athletes' management, packs, the market, the old live match, the museum and OAuth went (20 October 2026)
        for (path in listOf("/api/v1/club/athletes", "/api/v1/club/packs", "/api/v1/club/offer", "/api/v1/market", "/api/v1/live/recent", "/api/v1/club/museum", "/api/v1/club/scouting", "/api/v1/club/lineup/football", "/api/v1/store/offers", "/api/v1/club/blocks"))
            assertEquals(404, client.get(path) { header(HttpHeaders.Authorization, "Bearer $t") }.status.value, path)
        for (path in listOf("/api/v1/club/academy/claim", "/api/v1/club/trainings", "/api/v1/club/rest", "/api/v1/live/start", "/api/v1/club/medical/speedup", "/api/v1/auth/oauth/google", "/api/v1/auth/logout", "/api/v1/challenges/mine"))
            assertEquals(404, client.post(path) { header(HttpHeaders.Authorization, "Bearer $t") }.status.value, path)
        // opening the academy costs 3 000 coins
        call(HttpMethod.Post, "/club/spots/academy/unlock", t, expect = 402)
        sql("UPDATE clubs SET coins = 10000 WHERE name = 'Api FC'")
        val opened = call(HttpMethod.Post, "/club/spots/academy/unlock", t).data.o()
        assertEquals(7000, opened["club"]!!.o()["coins"]!!.jsonPrimitive.int)
        assertEquals(2, opened["stadium"]!!.jsonPrimitive.int); assertEquals(listOf("stadium", "academy"), opened["spots"]!!.strings())
        call(HttpMethod.Post, "/club/spots/academy/unlock", t, expect = 409)
        call(HttpMethod.Post, "/club/spots/castle/unlock", t, expect = 404)
        call(HttpMethod.Post, "/club/spots/beast_dome/unlock", t, expect = 400)
        assertTrue("academyNextAt" !in opened["club"]!!.o() && "live" !in opened)
    }

    @Test
    fun `no friends yet, the islands over HTTP`() = testApplication {
        environment { config = TestDb.appConfig() }
        application { module() }
        val t = guestWithClub("it-device-friends", "Friends FC")
        assertTrue(call(HttpMethod.Get, "/friends", t).data.jsonArray.isEmpty())   // no bots, no friends yet (V55)
        // a club's grounds: its open spots and islands
        val own = call(HttpMethod.Get, "/club", t).data.o()["club"]!!.o()["id"]!!.jsonPrimitive.content
        val g = call(HttpMethod.Get, "/clubs/$own/grounds", t).data.o()
        assertEquals(1, g["stadium"]!!.jsonPrimitive.int); assertEquals(listOf("stadium"), g["spots"]!!.strings())
        assertTrue("buildings" !in g)
        // GET /club sends the islands: the main one open, every people's island locked with its stadium
        val land = call(HttpMethod.Get, "/club", t).data.o()["land"]!!.o()
        assertEquals(listOf("main"), land["regions"]!!.strings())
        assertEquals(listOf("beast", "stone", "android", "shadow"), land["locked"]!!.jsonArray.map { it.o()["id"]!!.jsonPrimitive.content })
        assertEquals(setOf("BEAST", "STONE", "ANDROID", "SHADOW"), land["locked"]!!.jsonArray.map { it.o()["people"]!!.jsonPrimitive.content }.toSet())
        assertTrue(land["locked"]!!.jsonArray.all { it.o()["state"]!!.jsonPrimitive.content == "LOCKED" })
        assertEquals(emptyList<String>(), land["peoples"]!!.strings())
        val bridges = call(HttpMethod.Get, "/club/zones", t).data.jsonArray.map { it.o() }
        assertEquals(mapOf("BEAST" to 4, "STONE" to 5, "ANDROID" to 7, "SHADOW" to 9), bridges.associate { it["people"]!!.jsonPrimitive.content to it["requiredStadium"]!!.jsonPrimitive.int })
        assertEquals("Потрібен стадіон рівня 4", call(HttpMethod.Post, "/club/zones/basketball/buy", t, expect = 400, lang = "uk").o()["errors"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun `roads and collections over HTTP`() = testApplication {
        environment { config = TestDb.appConfig() }
        application { module() }
        val t = guestWithClub("it-device-fence", "Fence FC")
        val roads = call(HttpMethod.Get, "/club/roads", t).data.jsonArray
        assertEquals(listOf("TROPHY", "LEVEL"), roads.map { it.o()["road"]!!.jsonPrimitive.content })
        call(HttpMethod.Post, "/club/roads/trophy/1/claim", t, expect = 400)   // 0 trophies
        assertEquals(0, call(HttpMethod.Get, "/club/collections", t).data.o()["incomePct"]!!.jsonPrimitive.int)
        val step = roads[0].o()["steps"]!!.jsonArray[0].o()
        assertEquals(300, step["reward"]!!.o()["coins"]!!.jsonPrimitive.int); assertTrue("pack" !in step["reward"]!!.o())
    }

    @Test
    fun `alliances over HTTP - create, join, chat, tournament, likes`() = testApplication {
        environment { config = TestDb.appConfig() }
        application { module() }
        val a = guestWithClub("it-device-al-a", "Ally One")
        val b = guestWithClub("it-device-al-b", "Ally Two")
        call(HttpMethod.Post, "/alliances", a, """{"name":"Night Owls","tag":"NO"}""", expect = 400)   // stadium 1
        sql("INSERT INTO club_spots (club_id, spot, unlocked_at) SELECT id, 'academy', now() FROM clubs WHERE name = 'Ally One'")   // stadium 2
        val id = call(HttpMethod.Post, "/alliances", a, """{"name":"Night Owls","tag":"NO"}""", expect = 201).data.o()["alliance"]!!.o()["id"]!!.jsonPrimitive.content
        call(HttpMethod.Post, "/alliances/$id/join", b)
        val members = call(HttpMethod.Get, "/club/alliance", a).data.o()["members"]!!.jsonArray
        assertEquals(2, members.size)
        // presence: both made authenticated requests just now (clubs.last_seen_at, V42)
        assertTrue(members.all { it.o()["online"]!!.jsonPrimitive.boolean && it.o()["lastSeenAt"]!!.jsonPrimitive.content.isNotEmpty() }, members.toString())
        java.sql.DriverManager.getConnection(TestDb.url, TestDb.user, TestDb.password).use { it.createStatement().executeUpdate("UPDATE clubs SET last_seen_at = now() - interval '20 minutes' WHERE name = 'Ally Two'") }
        val two = call(HttpMethod.Get, "/club/alliance", a).data.o()["members"]!!.jsonArray.first { it.o()["club"]!!.o()["name"]!!.jsonPrimitive.content == "Ally Two" }.o()
        assertEquals(false, two["online"]!!.jsonPrimitive.boolean)
        call(HttpMethod.Post, "/club/alliance/messages", b, """{"text":"hello allies"}""", expect = 201)
        assertEquals("hello allies", call(HttpMethod.Get, "/club/alliance/messages", a).data.jsonArray.last().o()["text"]!!.jsonPrimitive.content)
        call(HttpMethod.Get, "/alliances/tournament", a)
        val bId = call(HttpMethod.Get, "/club", b).data.o()["club"]!!.o()["id"]!!.jsonPrimitive.content
        assertEquals(1, call(HttpMethod.Post, "/clubs/$bId/like", a).data.o()["likes"]!!.jsonPrimitive.int)
        assertEquals(1, call(HttpMethod.Get, "/clubs/top-liked", a).data.jsonArray.size)
        call(HttpMethod.Post, "/club/alliance/leave", b)
        call(HttpMethod.Post, "/club/alliance/leave", a)
        call(HttpMethod.Get, "/alliances/$id", a, expect = 404)
        // live game: events, mail
        call(HttpMethod.Get, "/events", a)
        assertEquals(0, call(HttpMethod.Get, "/club/mail", a).data.jsonArray.size)
    }

    @Test
    fun `chat, challenges, invite landing pages and app links over HTTP`() = testApplication {
        environment { config = TestDb.appConfig(mapOf("links.baseUrl" to "https://arenacity.test", "links.androidCertSha256" to "AA:BB", "links.iosTeamId" to "TEAM1")) }
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
        assertEquals("https://arenacity.test/c/$cc", challenge["link"]!!.jsonPrimitive.content)
        val m = call(HttpMethod.Post, "/challenges/$cc/accept", b, expect = 201).data.o()
        assertEquals("CHALLENGE", m["fixture"]!!.o()["kind"]!!.jsonPrimitive.content)
        call(HttpMethod.Post, "/challenges/$cc/accept", b, expect = 409)
        val clubA = call(HttpMethod.Get, "/club", a).data.o()["club"]!!.o()["id"]!!.jsonPrimitive.content
        assertEquals(1, call(HttpMethod.Get, "/clubs/$clubA/h2h", b).data.o()["fixtures"]!!.jsonArray.size)
        // a private league mate and a challenge opponent is a friend, listed once (19 Oct 2026)
        val friendsOfB = call(HttpMethod.Get, "/friends", b).data.jsonArray
        assertEquals(listOf("Alpha FC"), friendsOfB.map { it.o()["club"]!!.o()["name"]!!.jsonPrimitive.content })
        assertEquals(listOf("Bravo FC"), call(HttpMethod.Get, "/friends", a).data.jsonArray.map { it.o()["club"]!!.o()["name"]!!.jsonPrimitive.content })
        val grounds = call(HttpMethod.Get, "/clubs/$clubA/grounds", b).data.o()
        assertEquals(1, grounds["stadium"]!!.jsonPrimitive.int); assertEquals(listOf("stadium"), grounds["spots"]!!.strings())
        val landing = client.get("/l/$code") { header(HttpHeaders.AcceptLanguage, "ru-RU,ru;q=0.9") }
        val html = landing.bodyAsText()
        assertEquals(200, landing.status.value); assertTrue(html.contains(code) && html.contains("arenacity://l/$code") && html.contains("приглашает"))
        assertTrue(html.contains("Office &lt;b&gt;"), "club and league names are escaped")
        assertEquals(404, client.get("/l/NOPE99").status.value)
        assertTrue(client.get("/c/$cc").bodyAsText().contains("Alpha FC"))
        assertTrue(client.get("/.well-known/assetlinks.json").bodyAsText().contains("\"AA:BB\""))
        assertTrue(client.get("/.well-known/apple-app-site-association").bodyAsText().contains("TEAM1.com.inowios.arenacity"))
        assertEquals("https://arenacity.test", Json.parseToJsonElement(client.get("/api/v1/config").bodyAsText()).data.o()["publicBaseUrl"]!!.jsonPrimitive.content)
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
    fun `energy and the Arena match start over HTTP`() = testApplication {
        environment { config = TestDb.appConfig() }
        application { module() }
        val t = guestWithClub("it-device-energy", "Energy FC")
        val e0 = call(HttpMethod.Get, "/club", t).data.o()["energy"]!!.o()
        assertEquals(30, e0["current"]!!.jsonPrimitive.int); assertEquals(30, e0["max"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, e0["nextAt"]); assertEquals(40, e0["refillPrice"]!!.jsonPrimitive.int)
        call(HttpMethod.Post, "/club/energy/refill", t, expect = 409)   // already full
        assertEquals(25, call(HttpMethod.Post, "/club/arena/start", t).data.o()["energy"]!!.o()["current"]!!.jsonPrimitive.int)
        call(HttpMethod.Post, "/friendlies", t, "{}", expect = 201)
        val e1 = call(HttpMethod.Get, "/club/energy", t).data.o()
        assertEquals(22, e1["current"]!!.jsonPrimitive.int); assertTrue(e1["nextAt"] !is JsonNull)
        repeat(3) { call(HttpMethod.Post, "/club/arena/start", t) }
        call(HttpMethod.Post, "/club/cards/start", t)   // the card era's path, an alias for one client version
        val short = call(HttpMethod.Post, "/club/arena/start", t, expect = 400, lang = "uk")
        assertEquals("Недостатньо енергії — зачекайте, поки вона відновиться, або поповніть її", short.o()["errors"]!!.jsonArray.single().jsonPrimitive.content)
        val refill = call(HttpMethod.Post, "/club/energy/refill", t).data.o()   // 50 gems at the start
        assertEquals(30, refill["energy"]!!.o()["current"]!!.jsonPrimitive.int); assertEquals(10, refill["club"]!!.o()["gold"]!!.jsonPrimitive.int)
        // the weekly arena chest by the trophies: open once a week
        val chest = call(HttpMethod.Get, "/live/chest", t).data.o()
        assertEquals("YARD", chest["arena"]!!.jsonPrimitive.content); assertEquals(true, chest["available"]!!.jsonPrimitive.boolean)
        assertEquals(false, call(HttpMethod.Post, "/live/chest/claim", t).data.o()["available"]!!.jsonPrimitive.boolean)
        call(HttpMethod.Post, "/live/chest/claim", t, expect = 409)
    }

    @Test
    fun `heroes - the start six, unlocking for gold and match experience over HTTP`() = testApplication {
        environment { config = TestDb.appConfig() }
        application { module() }
        val t = guestWithClub("it-device-heroes", "Hero FC")
        val roster = call(HttpMethod.Get, "/club/heroes", t).data.o()
        val list = roster["heroes"]!!.jsonArray.map { it.o() }
        assertEquals(51, list.size); assertEquals(6, roster["owned"]!!.jsonPrimitive.int); assertEquals(1, roster["stadium"]!!.jsonPrimitive.int)
        assertEquals(setOf("p001", "p003", "p007", "p009", "p004", "p011"), list.filter { it["owned"]!!.jsonPrimitive.boolean }.map { it["id"]!!.jsonPrimitive.content }.toSet())
        val kai = list.first { it["id"]!!.jsonPrimitive.content == "p001" }
        assertEquals("START", kai["source"]!!.jsonPrimitive.content); assertEquals(1, kai["level"]!!.jsonPrimitive.int); assertEquals(100, kai["xpToNext"]!!.jsonPrimitive.int)
        assertEquals((0..3).map { "p001.s$it" }, kai["skills"]!!.strings()); assertEquals(listOf("p001.p0"), kai["passives"]!!.strings()); assertEquals("speed", kai["colour"]!!.jsonPrimitive.content)
        assertEquals(true, kai["hasKit"]!!.jsonPrimitive.boolean); assertEquals(JsonNull, kai["reason"]); assertTrue("unlockedSkills" !in kai && "boostPct" !in kai)
        assertEquals(listOf("p004.a0"), list.first { it["id"]!!.jsonPrimitive.content == "p004" }["alternates"]!!.strings())
        assertEquals(3, roster["maxPerMatch"]!!.jsonPrimitive.int); assertTrue("skillLevels" !in roster)
        val shizuku = list.first { it["id"]!!.jsonPrimitive.content == "p010" }
        assertEquals("STORY", shizuku["unlock"]!!.o()["kind"]!!.jsonPrimitive.content); assertEquals(2, shizuku["unlock"]!!.o()["chapter"]!!.jsonPrimitive.int)
        assertEquals(300, shizuku["unlock"]!!.o()["price"]!!.jsonPrimitive.int); assertEquals(false, shizuku["canBuy"]!!.jsonPrimitive.boolean)
        val beast = list.first { it["id"]!!.jsonPrimitive.content == "p012" }
        assertEquals("BEAST", beast["folk"]!!.jsonPrimitive.content); assertEquals(4, beast["unlock"]!!.o()["stadium"]!!.jsonPrimitive.int)
        assertEquals(false, beast["available"]!!.jsonPrimitive.boolean); assertEquals("SOON", beast["reason"]!!.jsonPrimitive.content)
        assertEquals(false, beast["hasKit"]!!.jsonPrimitive.boolean); assertEquals(0, beast["skills"]!!.jsonArray.size)
        call(HttpMethod.Post, "/club/heroes/p006/unlock", t, expect = 402)   // 500 gold, 50 owned
        java.sql.DriverManager.getConnection(TestDb.url, TestDb.user, TestDb.password).use { it.createStatement().executeUpdate("UPDATE clubs SET gold = 2000 WHERE name = 'Hero FC'") }
        val rei = call(HttpMethod.Post, "/club/heroes/p006/unlock", t).data.o()
        assertEquals(500, rei["spent"]!!.jsonPrimitive.int); assertEquals(1500, rei["club"]!!.o()["gold"]!!.jsonPrimitive.int)
        assertEquals("CRYSTALS", rei["hero"]!!.o()["source"]!!.jsonPrimitive.content)
        assertEquals(0, call(HttpMethod.Post, "/club/heroes/p006/unlock", t).data.o()["spent"]!!.jsonPrimitive.int)   // idempotent
        assertEquals("Незабаром", call(HttpMethod.Post, "/club/heroes/p012/unlock", t, expect = 400, lang = "uk").o()["errors"]!!.jsonArray.single().jsonPrimitive.content)   // no Arena kit yet
        call(HttpMethod.Post, "/club/heroes/p999/unlock", t, expect = 404)
        assertEquals(7, call(HttpMethod.Get, "/club/heroes", t).data.o()["owned"]!!.jsonPrimitive.int)
        // an Arena match result: the heroes who played earn by the result
        val result = call(HttpMethod.Post, "/club/arena/result", t, """{"seed":5,"won":true,"goalsFor":2,"goalsAgainst":0,"heroes":["p001","p006","p010"]}""").data.o()
        assertEquals(50, result["heroXp"]!!.jsonPrimitive.int)
        assertEquals(listOf("p001", "p006"), result["heroes"]!!.jsonArray.map { it.o()["id"]!!.jsonPrimitive.content })
        assertEquals(50, result["heroes"]!!.jsonArray[0].o()["xp"]!!.jsonPrimitive.int)
        call(HttpMethod.Post, "/club/arena/result", t, """{"seed":6,"won":true,"heroes":["p001","p003","p007","p009"]}""", expect = 400)   // a triple at most
        // the Arena triple («Склад»): none saved, then saved and validated
        val none = call(HttpMethod.Get, "/club/heroes/lineup", t).data.o()
        assertEquals(false, none["saved"]!!.jsonPrimitive.boolean); assertEquals(0, none["heroes"]!!.jsonArray.size); assertEquals(3, none["max"]!!.jsonPrimitive.int)
        val saved = call(HttpMethod.Put, "/club/heroes/lineup", t, """{"heroes":["p001","p011","p006"]}""").data.o()
        assertEquals(true, saved["saved"]!!.jsonPrimitive.boolean); assertEquals(listOf("p001", "p011", "p006"), saved["heroes"]!!.strings())
        assertEquals(listOf("p001", "p011", "p006"), call(HttpMethod.Get, "/club/heroes/lineup", t).data.o()["heroes"]!!.strings())
        call(HttpMethod.Put, "/club/heroes/lineup", t, """{"heroes":["p010"]}""", expect = 400)   // not owned
        call(HttpMethod.Put, "/club/heroes/lineup", t, """{"heroes":["p001","p003","p007","p009"]}""", expect = 400)   // four
        call(HttpMethod.Put, "/club/heroes/lineup", t, """{"heroes":["p001","p001"]}""", expect = 400)   // twice
        // training: at the stadium's level (stadium 1 from the start) for coins, the experience after the timer
        java.sql.DriverManager.getConnection(TestDb.url, TestDb.user, TestDb.password).use { it.createStatement().executeUpdate("UPDATE clubs SET coins = 50000 WHERE name = 'Hero FC'") }
        val offer = call(HttpMethod.Get, "/club/heroes/training", t).data.o()
        assertEquals(true, offer["available"]!!.jsonPrimitive.boolean); assertEquals(1, offer["slots"]!!.jsonPrimitive.int); assertEquals(1, offer["centerLevel"]!!.jsonPrimitive.int)
        assertEquals(200, offer["coins"]!!.jsonPrimitive.int); assertEquals(80, offer["xp"]!!.jsonPrimitive.int)
        val started = call(HttpMethod.Post, "/club/heroes/p003/train", t, expect = 201).data.o()
        assertEquals(200, started["spent"]!!.jsonPrimitive.int); assertEquals(1, started["training"]!!.o()["busy"]!!.jsonPrimitive.int)
        assertEquals(7200, started["hero"]!!.o()["training"]!!.o()["secondsLeft"]!!.jsonPrimitive.int)
        call(HttpMethod.Post, "/club/heroes/p001/train", t, expect = 409)   // the one slot is busy
        call(HttpMethod.Post, "/club/heroes/p003/train/claim", t, expect = 409)   // still running
        java.sql.DriverManager.getConnection(TestDb.url, TestDb.user, TestDb.password).use { it.createStatement().executeUpdate("UPDATE club_heroes SET training_ends_at = now() - interval '1 minute' WHERE hero_id = 'p003'") }
        val p003 = call(HttpMethod.Get, "/club/heroes", t).data.o()["heroes"]!!.jsonArray.map { it.o() }.first { it["id"]!!.jsonPrimitive.content == "p003" }
        assertEquals(true, p003["training"]!!.o()["done"]!!.jsonPrimitive.boolean)
        val claimed = call(HttpMethod.Post, "/club/heroes/p003/train/claim", t).data.o()
        assertEquals(80, claimed["gained"]!!.jsonPrimitive.int); assertEquals(80, claimed["hero"]!!.o()["xp"]!!.jsonPrimitive.int)
        assertEquals(kotlinx.serialization.json.JsonNull, claimed["hero"]!!.o()["training"]); assertEquals(0, claimed["training"]!!.o()["busy"]!!.jsonPrimitive.int)
    }

    @Test
    fun `story - chapter goals from Arena match reports, claiming a chapter, the story hero over HTTP`() = testApplication {
        environment { config = TestDb.appConfig() }
        application { module() }
        val t = guestWithClub("it-device-story", "Story FC")
        val s0 = call(HttpMethod.Get, "/club/story", t).data.o()
        assertEquals("spark", s0["season"]!!.jsonPrimitive.content); assertEquals(1, s0["chapter"]!!.jsonPrimitive.int); assertEquals(false, s0["finished"]!!.jsonPrimitive.boolean)
        val chapters = s0["chapters"]!!.jsonArray.map { it.o() }
        assertEquals(6, chapters.size)
        assertEquals(listOf("ACTIVE", "LOCKED", "LOCKED", "LOCKED", "LOCKED", "LOCKED"), chapters.map { it["state"]!!.jsonPrimitive.content })
        assertEquals("story.c1.intro", chapters[0]["introKey"]!!.jsonPrimitive.content)
        assertEquals(listOf("p002", "p008", "p004"), chapters[5]["rival"]!!.o()["heroes"]!!.strings()); assertTrue("strength" !in chapters[5]["rival"]!!.o())
        assertEquals("p010", chapters[1]["reward"]!!.o()["hero"]!!.jsonPrimitive.content)
        call(HttpMethod.Post, "/club/story/1/claim", t, expect = 409)
        // a win with Kai and Itsuki; then the chapter's story match (every goal counts matches: the Arena v4 is won 1:0)
        val r1 = call(HttpMethod.Post, "/club/arena/result", t, """{"seed":1,"won":true,"heroes":["p001","p003"]}""").data.o()
        assertEquals(emptyList<String>(), r1["story"]!!.o()["completed"]!!.jsonArray.map { it.jsonPrimitive.content })
        val r2 = call(HttpMethod.Post, "/club/cards/result", t, """{"seed":2,"won":true,"heroes":["p001","p003"],"story":1}""").data.o()   // the alias path
        assertEquals(listOf("c1.o1", "c1.o2", "c1.o3"), r2["story"]!!.o()["completed"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(true, r2["story"]!!.o()["chapterDone"]!!.jsonPrimitive.boolean)
        assertEquals("DONE", r2["story"]!!.o()["chapter"]!!.o()["state"]!!.jsonPrimitive.content)
        call(HttpMethod.Post, "/club/story/2/claim", t, expect = 409)
        call(HttpMethod.Post, "/club/story/x/claim", t, expect = 404)
        val c1 = call(HttpMethod.Post, "/club/story/1/claim", t).data.o()
        assertEquals(500, c1["coins"]!!.jsonPrimitive.int); assertEquals(5, c1["gems"]!!.jsonPrimitive.int)
        assertEquals(kotlinx.serialization.json.JsonNull, c1["hero"])
        assertEquals(2, c1["story"]!!.o()["chapter"]!!.jsonPrimitive.int)
        assertEquals("ACTIVE", c1["story"]!!.o()["chapters"]!!.jsonArray[1].o()["state"]!!.jsonPrimitive.content)
        call(HttpMethod.Post, "/club/story/1/claim", t, expect = 409)
        // chapter 2 finished (as if played): claiming brings Mirai into the club
        java.sql.DriverManager.getConnection(TestDb.url, TestDb.user, TestDb.password).use {
            it.createStatement().executeUpdate("UPDATE club_story SET progress = 'c2.o1=2,c2.o2=5,c2.o3=1' WHERE club_id = (SELECT id FROM clubs WHERE name = 'Story FC')")
        }
        val c2 = call(HttpMethod.Post, "/club/story/2/claim", t).data.o()
        assertEquals("p010", c2["hero"]!!.o()["id"]!!.jsonPrimitive.content); assertEquals(true, c2["newHero"]!!.jsonPrimitive.boolean)
        assertEquals("STORY", c2["hero"]!!.o()["source"]!!.jsonPrimitive.content)
        assertEquals(3, call(HttpMethod.Get, "/club/story", t).data.o()["chapter"]!!.jsonPrimitive.int)
        assertEquals(true, call(HttpMethod.Get, "/club/heroes", t).data.o()["heroes"]!!.jsonArray.map { it.o() }.first { it["id"]!!.jsonPrimitive.content == "p010" }["owned"]!!.jsonPrimitive.boolean)
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
        assertEquals(404, client.post("/api/v1/club/builders") { header(HttpHeaders.Authorization, "Bearer $a") }.status.value)   // no builders since the five islands map
    }

    @Test
    fun `coin packs, achievements and ad callbacks over HTTP`() = testApplication {
        environment { config = TestDb.appConfig() }
        application { module() }
        val t = guestWithClub("it-device-0201", "Cosmo FC")
        // the cosmetics shop is gone
        assertEquals(404, client.get("/api/v1/catalog/cosmetics").status.value)
        assertEquals(404, client.post("/api/v1/club/cosmetics/kit_stripes/buy") { header(HttpHeaders.Authorization, "Bearer $t") }.status.value)
        val poor = call(HttpMethod.Post, "/club/shop/coins/coins_medium", t, expect = 402, lang = "ru-RU,ru;q=0.9")   // 100 gold, 50 owned
        assertEquals("Недостаточно золота", poor.o()["errors"]!!.jsonArray[0].jsonPrimitive.content)
        val me = call(HttpMethod.Patch, "/me", t, """{"locale":"uk-UA"}""").data.o()
        assertEquals("uk", me["user"]!!.o()["locale"]!!.jsonPrimitive.content)
        call(HttpMethod.Post, "/purchases/verify", t, """{"store":"DEV","productId":"gold_small","token":"dev:it-cos"}""")
        // coins for gold
        val coinsBefore = call(HttpMethod.Get, "/club", t).data.o()["club"]!!.o()["coins"]!!.jsonPrimitive.long
        val afterPack = call(HttpMethod.Post, "/club/shop/coins/coins_small", t).data.o()
        assertEquals(coinsBefore + 3_000, afterPack["coins"]!!.jsonPrimitive.long); assertEquals(125, afterPack["gold"]!!.jsonPrimitive.int)
        // achievements from real counters (friendly played) and the login streak maximum
        call(HttpMethod.Post, "/friendlies", t, "{}", expect = 201)
        call(HttpMethod.Post, "/club/daily/claim", t)
        val list = call(HttpMethod.Get, "/club/achievements", t).data.jsonArray.associateBy { it.o()["id"]!!.jsonPrimitive.content }
        assertEquals(1, list.getValue("friendly_face").o()["value"]!!.jsonPrimitive.int)
        assertEquals(1, list.getValue("loyal").o()["value"]!!.jsonPrimitive.int)
        val claim = call(HttpMethod.Post, "/club/achievements/friendly_face/claim", t).data.o()
        assertEquals(5, claim["gold"]!!.jsonPrimitive.int)
        call(HttpMethod.Post, "/club/achievements/friendly_face/claim", t, expect = 409)
        // no rewarded placements any more (the double income and the free speed-up went with the builder); unsigned callbacks are refused
        assertEquals(404, client.post("/api/v1/ads/reward") { header(HttpHeaders.Authorization, "Bearer $t") }.status.value)
        assertEquals(403, client.get("/api/v1/ads/ssv?transaction_id=1&user_id=x").status.value)
    }
    @Test
    fun `admin, metrics, analytics events and legal pages over HTTP`() = testApplication {
        val token = "it-admin-token-0123456789"
        environment { config = TestDb.appConfig(mapOf("admin.token" to token, "links.supportEmail" to "help@arenacity.test")) }
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
        // the first-ten-minutes funnel and the balance dashboard read the real tables
        val funnel = admin(HttpMethod.Get, "/admin/funnel?days=7").data.o()
        assertEquals(1, funnel["cohort"]!!.jsonPrimitive.int)
        assertEquals(1, funnel["steps"]!!.jsonArray.first().o()["users"]!!.jsonPrimitive.int)
        val bal = admin(HttpMethod.Get, "/admin/balance?days=7").data.o()
        assertEquals(1, bal["activeClubs"]!!.jsonPrimitive.int)
        assertTrue(bal["spots"]!!.o().isEmpty())   // a new club: only the stadium, which is not counted
        assertEquals(1, bal["stadium"]!!.o()["1"]!!.jsonPrimitive.int)
        admin(HttpMethod.Get, "/admin/balance?days=0", expect = 400)
        val page = client.get("/admin/dashboard")
        assertEquals(200, page.status.value); assertTrue(page.bodyAsText().contains("X-Admin-Token"))
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
        assertTrue(privacy.contains("Privacy Policy") && privacy.contains("mailto:help@arenacity.test"))
        assertTrue(client.get("/delete-account?lang=ru").bodyAsText().contains("Удаление аккаунта"))
        assertTrue(client.get("/terms") { header(HttpHeaders.AcceptLanguage, "ru-RU,ru;q=0.9") }.bodyAsText().contains("Условия использования"))
        // account deletion erases the player's own events
        call(HttpMethod.Delete, "/me", t)
        assertEquals(1, admin(HttpMethod.Get, "/admin/stats").data.o()["counts"]!!.o()["analyticsEvents"]!!.jsonPrimitive.int)   // only the anonymous account_deleted
    }
}
