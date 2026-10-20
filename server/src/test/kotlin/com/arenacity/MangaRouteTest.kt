package com.arenacity

import com.arenacity.core.plugins.configureSerialization
import com.arenacity.core.plugins.configureStatusPages
import com.arenacity.presentation.route.mangaCatalogRoutes
import com.arenacity.presentation.route.mangaFileRoutes
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MangaRouteTest {
    private fun app(dir: File, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureSerialization(); configureStatusPages()
            routing { mangaFileRoutes(dir); route("/api/v1") { mangaCatalogRoutes(dir) } }
        }
        block()
    }

    @Test
    fun `no folder gives an empty catalog`() = app(File("does-not-exist-manga")) {
        val r = client.get("/api/v1/manga")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"episodes\":[]"), r.bodyAsText())
    }

    @Test
    fun `catalog and pages are served, other paths are not`() {
        val dir = Files.createTempDirectory("manga").toFile()
        File(dir, "catalog.json").writeText(
            """{"episodes":[{"id":"ep01","number":1,"title":"The Boy with the Spark","lang":"en","pages":[{"n":1,"v":"ab12cd34"},{"n":2,"v":"ef56ab78"}]}]}""")
        val page = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        File(dir, "ep01").mkdirs(); File(dir, "ep01/01.jpg").writeBytes(page)
        File(dir, "secret.txt").writeText("no")
        app(dir) {
            val cat = client.get("/api/v1/manga").bodyAsText()
            assertTrue(cat.contains("\"id\":\"ep01\"") && cat.contains("\"v\":\"ef56ab78\"") && cat.contains("The Boy with the Spark"), cat)

            val ok = client.get("/manga/ep01/01.jpg")
            assertEquals(HttpStatusCode.OK, ok.status)
            assertContentEquals(page, ok.readRawBytes())
            assertTrue(ok.headers[HttpHeaders.CacheControl].orEmpty().contains("max-age"))

            assertEquals(HttpStatusCode.NotFound, client.get("/manga/ep01/02.jpg").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/manga/ep01/..%2Fsecret.txt").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/manga/..%2F/secret.txt").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/manga/ep01/catalog.json").status)
        }
        dir.deleteRecursively()
    }
}
