package com.matchdayclub.core.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

fun Application.configureCORS() {
    val hosts = environment.config.property("cors.allowedHosts").getString().split(",").map { it.trim() }.filter { it.isNotEmpty() }
    install(CORS) {
        allowMethod(HttpMethod.Options); allowMethod(HttpMethod.Put); allowMethod(HttpMethod.Delete); allowMethod(HttpMethod.Patch)
        allowHeader(HttpHeaders.Authorization); allowHeader(HttpHeaders.ContentType); allowHeader("X-Request-Id"); allowHeader("X-Platform"); allowHeader("X-Client-Version")
        exposeHeader("X-Request-Id")
        if (hosts.isEmpty()) anyHost() else hosts.forEach { allowHost(it, schemes = listOf("https")) }
    }
}
