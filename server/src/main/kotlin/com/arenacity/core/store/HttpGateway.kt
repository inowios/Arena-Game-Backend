package com.arenacity.core.store

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.content.TextContent
import io.ktor.http.*

data class HttpReply(val status: Int, val body: String)

/** Minimal HTTP seam for store/ad-network calls, so verifiers are testable without a network. */
fun interface HttpGateway {
    suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpReply
}

class KtorHttpGateway(private val client: HttpClient) : HttpGateway {
    override suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpReply {
        val resp = client.request(url) {
            this.method = HttpMethod.parse(method)
            var type = ContentType.Application.Json
            headers.forEach { (k, v) -> if (k.equals(HttpHeaders.ContentType, true)) type = ContentType.parse(v) else header(k, v) }
            if (body != null) setBody(TextContent(body, type))
        }
        return HttpReply(resp.status.value, resp.bodyAsText())
    }
}
