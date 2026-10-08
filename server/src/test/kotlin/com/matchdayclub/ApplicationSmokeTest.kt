package com.matchdayclub

import com.matchdayclub.common.response.toSuccessResponse
import com.matchdayclub.core.plugins.configureSerialization
import com.matchdayclub.core.plugins.configureStatusPages
import com.matchdayclub.domain.exception.NotFoundException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationSmokeTest {
    @Test
    fun `envelope and domain errors`() = testApplication {
        application {
            configureSerialization(); configureStatusPages()
            routing {
                get("/ping") { call.respond("pong".toSuccessResponse()) }
                get("/missing") { throw NotFoundException("Club not found") }
            }
        }
        val ok = client.get("/ping")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertTrue(ok.bodyAsText().contains("\"data\":\"pong\""))
        val nf = client.get("/missing")
        assertEquals(HttpStatusCode.NotFound, nf.status)
        assertTrue(nf.bodyAsText().contains("Club not found"))
    }
}
