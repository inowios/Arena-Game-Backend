package com.arenacity.data.repository

import com.arenacity.common.extension.dbQuery
import com.arenacity.data.database.ClubTable
import com.arenacity.data.database.PurchaseTable
import com.arenacity.data.database.UserDayTable
import com.arenacity.domain.model.Purchase
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.repository.OpsRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.util.UUID

class OpsRepositoryImpl(private val clubs: ClubRepository) : OpsRepository {
    private val countQueries = linkedMapOf(
        "users" to "SELECT count(*) FROM users WHERE auth_provider <> 'BOT' AND is_active",
        "clubs" to "SELECT count(*) FROM clubs WHERE NOT is_bot",
        "bots" to "SELECT count(*) FROM clubs WHERE is_bot",
        "publicLeagues" to "SELECT count(*) FROM leagues WHERE kind = 'PUBLIC' AND status = 'ACTIVE'",
        "privateLeagues" to "SELECT count(*) FROM leagues WHERE kind = 'PRIVATE'",
        "scheduledFixtures" to "SELECT count(*) FROM fixtures WHERE status = 'SCHEDULED'",
        "playedFixtures" to "SELECT count(*) FROM fixtures WHERE status = 'PLAYED'",
        "activeCups" to "SELECT count(*) FROM cups WHERE status = 'ACTIVE'",
        "purchases" to "SELECT count(*) FROM purchases WHERE status = 'GRANTED' AND store <> 'DEV'",
        "pushTokens" to "SELECT count(*) FROM push_tokens",
        "analyticsEvents" to "SELECT count(*) FROM analytics_events",
    )

    override suspend fun counts(): Map<String, Long> = dbQuery {
        countQueries.mapValues { (_, sql) -> TransactionManager.current().exec(sql) { rs -> rs.next(); rs.getLong(1) } ?: 0L }
    }

    override suspend fun searchClubs(q: String, limit: Int): List<com.arenacity.domain.model.Club> {
        val text = q.trim()
        if (text.isEmpty()) return emptyList()
        runCatching { UUID.fromString(text) }.getOrNull()?.let { id -> return listOfNotNull(clubs.findById(id) ?: clubs.findByUserId(id)) }
        val pattern = "%" + text.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val ids = dbQuery {
            ClubTable.select(ClubTable.id).where { ClubTable.name.lowerCase() like pattern }.orderBy(ClubTable.isBot to SortOrder.ASC, ClubTable.reputation to SortOrder.DESC)
                .limit(limit.coerceIn(1, 100)).map { it[ClubTable.id] }
        }
        val found = clubs.findByIds(ids).associateBy { it.id }
        return ids.mapNotNull { found[it] }
    }

    override suspend fun purchasesOf(clubId: UUID): List<Purchase> = dbQuery {
        PurchaseTable.selectAll().where { PurchaseTable.clubId eq clubId }.orderBy(PurchaseTable.createdAt, SortOrder.DESC).limit(100).map {
            Purchase(it[PurchaseTable.id], it[PurchaseTable.clubId], it[PurchaseTable.store], it[PurchaseTable.productId], it[PurchaseTable.externalId], it[PurchaseTable.status], it[PurchaseTable.grantedGold], it[PurchaseTable.grantedCoins], it[PurchaseTable.createdAt])
        }
    }

    override suspend fun lastActive(userId: UUID) = dbQuery {
        val max = UserDayTable.day.max()
        UserDayTable.select(max).where { UserDayTable.userId eq userId }.singleOrNull()?.get(max)
    }
}
