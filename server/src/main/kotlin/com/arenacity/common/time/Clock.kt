package com.arenacity.common.time

import java.time.Instant

/** Injectable time source so use cases are testable with a fixed clock. */
fun interface Clock {
    fun now(): Instant
}

object SystemClock : Clock {
    override fun now(): Instant = Instant.now()
}
