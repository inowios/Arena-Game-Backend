package com.matchdayclub.domain.repository

import com.matchdayclub.domain.model.InboxItem
import com.matchdayclub.domain.model.TransferCode
import java.time.Instant
import java.util.UUID

interface AccountRepository {
    /** Stores [code] and drops the user's older codes. */
    suspend fun createTransferCode(code: TransferCode)
    suspend fun findTransferCode(code: String): TransferCode?
    /** Marks the code used; false when it was already used (concurrent redeem). */
    suspend fun useTransferCode(code: String, at: Instant): Boolean
    suspend fun deletePushTokens(userId: UUID)
    /** Hides every chat line of a club (account deletion). */
    suspend fun hideClubMessages(clubId: UUID)
}

interface InboxRepository {
    suspend fun add(item: InboxItem)
    suspend fun list(clubId: UUID, limit: Int): List<InboxItem>
    suspend fun unread(clubId: UUID): Int
    suspend fun markAllRead(clubId: UUID, at: Instant)
}
