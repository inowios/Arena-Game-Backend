package com.arenacity.domain.repository

import java.time.Instant
import java.util.UUID

interface MarketRepository {
    /** Listing indexes already bought in this period / refresh. */
    suspend fun purchased(clubId: UUID, period: Long, salt: Int): Set<Int>
    /** Records a purchase; false when that listing was bought already (unique key). */
    suspend fun markPurchased(clubId: UUID, period: Long, salt: Int, idx: Int, athleteId: UUID, at: Instant): Boolean
}
