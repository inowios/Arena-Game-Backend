package com.matchdayclub.core.plugins

import com.matchdayclub.core.di.appModule
import io.ktor.server.application.*
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.configureKoin() {
    install(Koin) {
        slf4jLogger()
        modules(module { single { this@configureKoin } }, appModule)
    }
}
