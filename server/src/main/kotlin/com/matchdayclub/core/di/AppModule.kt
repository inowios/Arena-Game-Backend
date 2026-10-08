package com.matchdayclub.core.di

import com.matchdayclub.common.time.Clock
import com.matchdayclub.common.time.SystemClock
import org.koin.dsl.module

val appModule = module {
    single<Clock> { SystemClock }
}
