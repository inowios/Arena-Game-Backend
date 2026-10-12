package com.arenacity.common.tx

import com.arenacity.common.extension.dbQuery

/**
 * Unit-of-work boundary for use cases. Production runs the block in one database transaction;
 * unit tests with in-memory fakes use [NoTx].
 */
interface Tx {
    suspend operator fun <T> invoke(block: suspend () -> T): T
}

object NoTx : Tx {
    override suspend fun <T> invoke(block: suspend () -> T): T = block()
}

object DbTx : Tx {
    override suspend fun <T> invoke(block: suspend () -> T): T = dbQuery(block)
}
