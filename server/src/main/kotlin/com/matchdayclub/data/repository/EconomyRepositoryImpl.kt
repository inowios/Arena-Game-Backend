package com.matchdayclub.data.repository

import com.matchdayclub.common.extension.dbQuery
import com.matchdayclub.data.database.*
import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.repository.EconomyRepository
import com.matchdayclub.domain.repository.PushTokenRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
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
    override suspend fun createPurchase(p: Purchase) = dbQuery {
        PurchaseTable.insert { it[id] = p.id; it[clubId] = p.clubId; it[store] = p.store; it[productId] = p.productId; it[externalId] = p.externalId; it[status] = p.status; it[grantedGold] = p.grantedGold; it[grantedCoins] = p.grantedCoins; it[createdAt] = p.createdAt }; p
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
