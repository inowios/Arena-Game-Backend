package com.matchdayclub.data.repository

import com.matchdayclub.common.extension.dbQuery
import com.matchdayclub.data.database.*
import com.matchdayclub.domain.rules.ProgressEvent
import com.matchdayclub.domain.usecase.liveops.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import java.time.Instant
import java.util.UUID

class LiveOpsRepositoryImpl : LiveOpsRepository {
    override suspend fun adminEvents() = dbQuery {
        GameEventTable.selectAll().orderBy(GameEventTable.startsAt).map {
            GameEvent(it[GameEventTable.id], it[GameEventTable.key], it[GameEventTable.startsAt], it[GameEventTable.endsAt], it[GameEventTable.buildTimePct], it[GameEventTable.incomePct],
                it[GameEventTable.obstacleGoldPct], it[GameEventTable.goal]?.let { g -> runCatching { ProgressEvent.valueOf(g) }.getOrNull() }, it[GameEventTable.goalTarget],
                it[GameEventTable.rewardCoins], it[GameEventTable.rewardGold], it[GameEventTable.rewardItem])
        }
    }
    override suspend fun createEvent(e: GameEvent) {
        dbQuery {
            GameEventTable.insert { it[id] = e.id; it[key] = e.key; it[startsAt] = e.startsAt; it[endsAt] = e.endsAt; it[buildTimePct] = e.buildTimePct; it[incomePct] = e.incomePct
                it[obstacleGoldPct] = e.obstacleGoldPct; it[goal] = e.goal?.name; it[goalTarget] = e.goalTarget; it[rewardCoins] = e.rewardCoins; it[rewardGold] = e.rewardGold; it[rewardItem] = e.rewardItem }
        }
    }
    override suspend fun deleteEvent(id: String) { dbQuery { GameEventTable.deleteWhere { GameEventTable.id eq id } } }
    override suspend fun progress(clubId: UUID, ids: Collection<String>): Map<String, EventProgress> = if (ids.isEmpty()) emptyMap() else dbQuery {
        EventProgressTable.selectAll().where { (EventProgressTable.clubId eq clubId) and (EventProgressTable.eventId inList ids) }
            .associate { it[EventProgressTable.eventId] to EventProgress(it[EventProgressTable.eventId], it[EventProgressTable.count], it[EventProgressTable.claimedAt] != null) }
    }
    override suspend fun bump(clubId: UUID, eventId: String, cap: Int) {
        dbQuery {
            val n = EventProgressTable.update({ (EventProgressTable.clubId eq clubId) and (EventProgressTable.eventId eq eventId) and (EventProgressTable.count less cap) }) { it.update(count, count + 1) }
            if (n == 0) EventProgressTable.insertIgnore { it[this.eventId] = eventId; it[this.clubId] = clubId; it[count] = 1 }
        }
    }
    override suspend fun claimEvent(clubId: UUID, eventId: String, at: Instant) = dbQuery {
        EventProgressTable.update({ (EventProgressTable.clubId eq clubId) and (EventProgressTable.eventId eq eventId) and EventProgressTable.claimedAt.isNull() }) { it[claimedAt] = at } == 1
    }

    override suspend fun mails(clubId: UUID, now: Instant) = dbQuery {
        DevMailTable.selectAll().where { (DevMailTable.expiresAt greater now) and (DevMailTable.clubId.isNull() or (DevMailTable.clubId eq clubId)) }
            .orderBy(DevMailTable.createdAt, SortOrder.DESC).limit(30)
            .map { DevMail(it[DevMailTable.id], it[DevMailTable.title], it[DevMailTable.body], it[DevMailTable.coins], it[DevMailTable.gold], it[DevMailTable.item], it[DevMailTable.clubId], it[DevMailTable.createdAt], it[DevMailTable.expiresAt]) }
    }
    override suspend fun claimedMails(clubId: UUID) = dbQuery { DevMailClaimTable.selectAll().where { DevMailClaimTable.clubId eq clubId }.map { it[DevMailClaimTable.mailId] }.toSet() }
    override suspend fun createMail(m: DevMail) {
        dbQuery { DevMailTable.insert { it[id] = m.id; it[title] = m.title; it[body] = m.body; it[coins] = m.coins; it[gold] = m.gold; it[item] = m.item; it[clubId] = m.clubId; it[createdAt] = m.createdAt; it[expiresAt] = m.expiresAt } }
    }
    override suspend fun claimMail(clubId: UUID, mailId: UUID, at: Instant) = dbQuery {
        DevMailClaimTable.insertIgnore { it[this.mailId] = mailId; it[this.clubId] = clubId; it[claimedAt] = at }.insertedCount == 1
    }
    override suspend fun offerBought(clubId: UUID, day: Int, offerId: String) = dbQuery {
        OfferPurchaseTable.selectAll().where { (OfferPurchaseTable.clubId eq clubId) and (OfferPurchaseTable.day eq day) and (OfferPurchaseTable.offerId eq offerId) }.count() > 0
    }
    override suspend fun markOffer(clubId: UUID, day: Int, offerId: String, at: Instant) = dbQuery {
        OfferPurchaseTable.insertIgnore { it[this.clubId] = clubId; it[this.day] = day; it[this.offerId] = offerId; it[createdAt] = at }.insertedCount == 1
    }
}
