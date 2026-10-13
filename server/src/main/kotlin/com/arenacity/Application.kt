package com.arenacity

import com.arenacity.core.plugins.*
import io.ktor.server.application.*
import io.ktor.server.netty.*

fun main(args: Array<String>) = EngineMain.main(args)

fun Application.module() {
    configureKoin()
    configureDatabase()
    configureSecurity()
    configureRequestId()
    configureCORS()
    configureSerialization()
    configureMonitoring()
    configureStatusPages()
    configureRateLimiting()
    configureMatchdayScheduler()
    configureRouting()
    configureShowcase()
    configureLand()
}
