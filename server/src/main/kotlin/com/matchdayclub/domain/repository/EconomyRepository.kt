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
}

interface PushTokenRepository {
    suspend fun upsert(userId: UUID, token: String, platform: String)
    suspend fun delete(token: String)
    suspend fun tokensForUsers(userIds: Collection<UUID>): List<PushToken>
}
