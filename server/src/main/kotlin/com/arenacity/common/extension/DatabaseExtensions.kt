package com.arenacity.common.extension

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.supervisorScope
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Marks the coroutine of an open [dbQuery] transaction. */
private class InTransaction : AbstractCoroutineContextElement(InTransaction) { companion object Key : CoroutineContext.Key<InTransaction> }
private val inTransaction = InTransaction()

/**
 * Runs [block] in a suspended DB transaction under its own supervisorScope so a failing
 * transaction can never cancel the request-handler scope (see Arena_Backend 2026-09-14 incident).
 * Inside an already open transaction (a use case wrapped in [com.arenacity.common.tx.Tx]) the block joins it,
 * so a whole use case commits or rolls back as one unit.
 *
 * "Already open" is read from the coroutine context, not from Exposed's thread-local: when a transaction coroutine
 * completes, its caller can be resumed on the same pooled thread before the thread-local is restored (Ktor development
 * mode's class-loader-aware dispatcher does this), and joining that finished transaction fails with "Connection is closed".
 */
suspend fun <T> dbQuery(block: suspend () -> T): T {
    if (currentCoroutineContext()[InTransaction] != null) return block()
    return supervisorScope { newSuspendedTransaction(Dispatchers.IO + inTransaction) { block() } }
}
