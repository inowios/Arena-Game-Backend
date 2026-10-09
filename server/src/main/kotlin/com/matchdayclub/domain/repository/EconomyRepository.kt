package com.matchdayclub.domain.repository

import com.matchdayclub.domain.model.*
import java.time.LocalDate
import java.util.UUID

interface EconomyRepository {
    suspend fun pass(clubId: UUID, season: Int): SeasonPass?
    suspend fun upsertPass(pass: SeasonPass): SeasonPass
    suspend fun tasks(clubId: UUID, day: LocalDate): List<DailyTask>
    suspend fun upsertTask(task: DailyTask): DailyTask
    suspend fun purchaseByExternalId(externalId: String): Purchase?
    suspend fun createPurchase(p: Purchase): Purchase
    /** Records a verified rewarded-ad transaction; false when it was already granted (AdMob retries callbacks). */
    suspend fun recordAdReward(transactionId: String, clubId: UUID, placement: String, at: java.time.Instant): Boolean
}

interface InventoryRepository {
    suspend fun items(clubId: UUID): List<ClubItem>
    /** Adds the item; false when the club already owns it. */
    suspend fun add(item: ClubItem): Boolean
}

interface AchievementRepository {
    suspend fun counters(clubId: UUID): Map<com.matchdayclub.domain.rules.Metric, Long>
    suspend fun increment(clubId: UUID, metric: com.matchdayclub.domain.rules.Metric, by: Long = 1)
    /** Keeps the running maximum (BEST_STREAK). */
    suspend fun raiseTo(clubId: UUID, metric: com.matchdayclub.domain.rules.Metric, value: Long)
    /** Highest claimed tier per achievement id. */
    suspend fun claimed(clubId: UUID): Map<String, Int>
    suspend fun setClaimed(clubId: UUID, achievementId: String, tier: Int, at: java.time.Instant)
}

interface PushTokenRepository {
    suspend fun upsert(userId: UUID, token: String, platform: String)
    suspend fun delete(token: String)
    suspend fun tokensForUsers(userIds: Collection<UUID>): List<PushToken>
}
