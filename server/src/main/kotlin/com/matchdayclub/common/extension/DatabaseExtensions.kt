package com.matchdayclub.common.extension

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.supervisorScope
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/**
 * Runs [block] in a suspended DB transaction under its own supervisorScope so a failing
 * transaction can never cancel the request-handler scope (see Arena_Backend 2026-09-14 incident).
 * Inside an already open transaction (a use case wrapped in [com.matchdayclub.common.tx.Tx]) the block joins it,
 * so a whole use case commits or rolls back as one unit.
 */
suspend fun <T> dbQuery(block: suspend () -> T): T {
    if (runCatching { TransactionManager.currentOrNull() }.getOrNull() != null) return block()
    return supervisorScope { newSuspendedTransaction(Dispatchers.IO) { block() } }
}
