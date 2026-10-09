package com.matchdayclub.domain.model

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class SeasonPass(val clubId: UUID, val season: Int, val xp: Int, val premium: Boolean, val claimedFree: Int, val claimedPremium: Int)
data class DailyTask(val clubId: UUID, val day: LocalDate, val task: String, val progress: Int, val claimed: Boolean)
data class Purchase(val id: UUID, val clubId: UUID, val store: String, val productId: String, val externalId: String, val status: String, val grantedGold: Int, val grantedCoins: Long, val createdAt: Instant)
data class PushToken(val id: UUID, val userId: UUID, val token: String, val platform: String)
/** Cosmetic owned by a club ([itemId] from Cosmetics; [source] = SHOP / PASS / ACHIEVEMENT). */
data class ClubItem(val clubId: UUID, val itemId: String, val source: String, val acquiredAt: Instant)
