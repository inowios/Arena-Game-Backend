package com.matchdayclub.common.ops

/** Where background jobs report the errors they catch and survive (scheduler status, metrics and alerts). */
fun interface FailureSink { fun report(job: String, error: Throwable) }

object NoFailures : FailureSink { override fun report(job: String, error: Throwable) {} }
