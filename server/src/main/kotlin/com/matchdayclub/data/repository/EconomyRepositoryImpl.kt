package com.matchdayclub.data.repository

import com.matchdayclub.common.extension.dbQuery
import com.matchdayclub.data.database.*
import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.repository.AchievementRepository
import com.matchdayclub.domain.repository.EconomyRepository
import com.matchdayclub.domain.repository.InventoryRepository
import com.matchdayclub.domain.repository.PushTokenRepository
import com.matchdayclub.domain.rules.Metric
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class EconomyRepositoryImpl : EconomyRepository {
    override suspend fun pass(clubId: UUID, season: Int) = dbQuery {
        SeasonPassTable.selectAll().where { (SeasonPassTable.clubId eq clubId) and (SeasonPassTable.season eq season) }.singleOrNull()?.let { SeasonPass(clubId, season, it[SeasonPassTable.xp], it[SeasonPassTable.premium], it[SeasonPassTable.claimedFree], it[SeasonPassTable.claimedPremium]) }
    }
    override suspend fun upsertPass(pass: SeasonPass) = dbQuery {
        SeasonPassTable.upsert { it[clubId] = pass.clubId; it[season] = pass.season; it[xp] = pass.xp; it[premium] = pass.premium; it[claimedFree] = pass.claimedFree; it[claimedPremium] = pass.claimedPremium }; pass
    }
    override suspend fun tasks(clubId: UUID, day: LocalDate) = dbQuery {
        DailyTaskTable.selectAll().where { (DailyTaskTable.clubId eq clubId) and (DailyTaskTable.day eq day) }.map { DailyTask(clubId, day, it[DailyTaskTable.task], it[DailyTaskTable.progress], it[DailyTaskTable.claimed]) }
    }
    override suspend fun upsertTask(task: DailyTask) = dbQuery {
        DailyTaskTable.upsert { it[clubId] = task.clubId; it[day] = task.day; it[DailyTaskTable.task] = task.task; it[progress] = task.progress; it[claimed] = task.claimed }; task
    }
    override suspend fun purchaseByExternalId(externalId: String) = dbQuery {
        PurchaseTable.selectAll().where { PurchaseTable.externalId eq externalId }.singleOrNull()?.let { Purchase(it[PurchaseTable.id], it[PurchaseTable.clubId], it[PurchaseTable.store], it[PurchaseTable.productId], it[PurchaseTable.externalId], it[PurchaseTable.status], it[PurchaseTable.grantedGold], it[PurchaseTable.grantedCoins], it[PurchaseTable.createdAt]) }
    }
    override suspend fun hasPurchase(clubId: UUID, productId: String) = dbQuery {
        PurchaseTable.selectAll().where { (PurchaseTable.clubId eq clubId) and (PurchaseTable.productId eq productId) and (PurchaseTable.status eq "GRANTED") }.count() > 0
    }
    override suspend fun createPurchase(p: Purchase) = dbQuery {
        PurchaseTable.insert { it[id] = p.id; it[clubId] = p.clubId; it[store] = p.store; it[productId] = p.productId; it[externalId] = p.externalId; it[status] = p.status; it[grantedGold] = p.grantedGold; it[grantedCoins] = p.grantedCoins; it[createdAt] = p.createdAt }; p
    }
    override suspend fun recordAdReward(transactionId: String, clubId: UUID, placement: String, at: Instant): Boolean = dbQuery {
        AdRewardTable.insertIgnore { it[AdRewardTable.transactionId] = transactionId; it[AdRewardTable.clubId] = clubId; it[AdRewardTable.placement] = placement; it[createdAt] = at }.insertedCount == 1
    }
}

class InventoryRepositoryImpl : InventoryRepository {
    override suspend fun items(clubId: UUID) = dbQuery {
        ClubItemTable.selectAll().where { ClubItemTable.clubId eq clubId }.orderBy(ClubItemTable.acquiredAt).map { ClubItem(clubId, it[ClubItemTable.itemId], it[ClubItemTable.itemSource], it[ClubItemTable.acquiredAt]) }
    }
    override suspend fun add(item: ClubItem): Boolean = dbQuery {
        ClubItemTable.insertIgnore { it[clubId] = item.clubId; it[itemId] = item.itemId; it[itemSource] = item.source; it[acquiredAt] = item.acquiredAt }.insertedCount == 1
    }
}

class AchievementRepositoryImpl : AchievementRepository {
    override suspend fun counters(clubId: UUID) = dbQuery {
        ClubCounterTable.selectAll().where { ClubCounterTable.clubId eq clubId }
            .mapNotNull { r -> runCatching { Metric.valueOf(r[ClubCounterTable.metric]) }.getOrNull()?.let { it to r[ClubCounterTable.value] } }.toMap()
    }
    override suspend fun increment(clubId: UUID, metric: Metric, by: Long) {
        dbQuery {
            ClubCounterTable.upsert(onUpdate = { it[ClubCounterTable.value] = ClubCounterTable.value + by }) {
                it[ClubCounterTable.clubId] = clubId; it[ClubCounterTable.metric] = metric.name; it[value] = by
            }
        }
    }
    override suspend fun raiseTo(clubId: UUID, metric: Metric, value: Long) {
        dbQuery {
            ClubCounterTable.upsert(onUpdate = { it[ClubCounterTable.value] = CustomFunction("GREATEST", LongColumnType(), ClubCounterTable.value, longLiteral(value)) }) {
                it[ClubCounterTable.clubId] = clubId; it[ClubCounterTable.metric] = metric.name; it[ClubCounterTable.value] = value
            }
        }
    }
    override suspend fun claimed(clubId: UUID) = dbQuery {
        ClubAchievementTable.selectAll().where { ClubAchievementTable.clubId eq clubId }.associate { it[ClubAchievementTable.achievement] to it[ClubAchievementTable.tier] }
    }
    override suspend fun setClaimed(clubId: UUID, achievementId: String, tier: Int, at: Instant) {
        dbQuery {
            ClubAchievementTable.upsert { it[ClubAchievementTable.clubId] = clubId; it[achievement] = achievementId; it[ClubAchievementTable.tier] = tier; it[claimedAt] = at }
        }
    }
}

class PushTokenRepositoryImpl : PushTokenRepository {
    override suspend fun upsert(userId: UUID, token: String, platform: String) {
        dbQuery {
            val now = Instant.now()
            val updated = PushTokenTable.update({ PushTokenTable.token eq token }) { it[PushTokenTable.userId] = userId; it[PushTokenTable.platform] = platform; it[updatedAt] = now }
            if (updated == 0) PushTokenTable.insert { it[id] = UUID.randomUUID(); it[PushTokenTable.userId] = userId; it[PushTokenTable.token] = token; it[PushTokenTable.platform] = platform; it[createdAt] = now; it[updatedAt] = now }
        }
    }
    override suspend fun delete(token: String) { dbQuery { PushTokenTable.deleteWhere { PushTokenTable.token eq token } } }
    override suspend fun tokensForUsers(userIds: Collection<UUID>) = if (userIds.isEmpty()) emptyList() else dbQuery {
        PushTokenTable.selectAll().where { PushTokenTable.userId inList userIds }.map { PushToken(it[PushTokenTable.id], it[PushTokenTable.userId], it[PushTokenTable.token], it[PushTokenTable.platform]) }
    }
}
