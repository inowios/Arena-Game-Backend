package com.matchdayclub.data.repository

import com.matchdayclub.common.extension.dbQuery
import com.matchdayclub.data.database.*
import com.matchdayclub.domain.model.AnalyticsEvent
import com.matchdayclub.domain.model.CohortRow
import com.matchdayclub.domain.repository.AnalyticsRepository
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** PostgreSQL analytics queries (dates are ISO literals built from LocalDate, never from user input). */
class AnalyticsRepositoryImpl : AnalyticsRepository {
    private val propsSerializer = MapSerializer(String.serializer(), String.serializer())

    override suspend fun markActive(userId: UUID, day: LocalDate): Boolean = dbQuery {
        UserDayTable.insertIgnore { it[this.userId] = userId; it[this.day] = day }.insertedCount > 0
    }

    override suspend fun record(events: List<AnalyticsEvent>) {
        if (events.isEmpty()) return
        dbQuery {
            AnalyticsEventTable.batchInsert(events, shouldReturnGeneratedValues = false) { e ->
                this[AnalyticsEventTable.userId] = e.userId; this[AnalyticsEventTable.name] = e.name
                this[AnalyticsEventTable.props] = e.props.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(propsSerializer, it) }
                this[AnalyticsEventTable.platform] = e.platform; this[AnalyticsEventTable.appVersion] = e.appVersion; this[AnalyticsEventTable.createdAt] = e.at
            }
        }
    }

    override suspend fun deleteEventsOf(userId: UUID) { dbQuery { AnalyticsEventTable.deleteWhere { AnalyticsEventTable.userId eq userId } } }
    override suspend fun purgeEventsBefore(before: Instant): Int = dbQuery { AnalyticsEventTable.deleteWhere { AnalyticsEventTable.createdAt less before } }
    override suspend fun deleteActivityOf(userId: UUID) { dbQuery { UserDayTable.deleteWhere { UserDayTable.userId eq userId } } }
    override suspend fun purgeActivityBefore(day: LocalDate): Int = dbQuery { UserDayTable.deleteWhere { UserDayTable.day less day } }

    private fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): T = TransactionManager.current().exec(sql) { rs -> read(rs) }!!

    override suspend fun cohorts(from: LocalDate, to: LocalDate): List<CohortRow> = dbQuery {
        val day = "(u.created_at AT TIME ZONE 'UTC')::date"
        query("""
            SELECT $day AS cohort, count(*) AS installs,
              count(*) FILTER (WHERE EXISTS (SELECT 1 FROM user_days d WHERE d.user_id = u.id AND d.day = $day + 1)) AS d1,
              count(*) FILTER (WHERE EXISTS (SELECT 1 FROM user_days d WHERE d.user_id = u.id AND d.day = $day + 7)) AS d7
            FROM users u
            WHERE u.auth_provider <> 'BOT' AND u.provider_id NOT LIKE 'detached:%'
              AND u.created_at >= ('$from'::timestamp AT TIME ZONE 'UTC') AND u.created_at < ('${to.plusDays(1)}'::timestamp AT TIME ZONE 'UTC')
            GROUP BY 1 ORDER BY 1
        """.trimIndent()) { rs ->
            buildList { while (rs.next()) add(CohortRow(rs.getDate("cohort").toLocalDate(), rs.getInt("installs"), rs.getInt("d1"), rs.getInt("d7"))) }
        }
    }

    override suspend fun activeUsers(from: LocalDate, to: LocalDate): Int = dbQuery {
        val n = UserDayTable.userId.countDistinct()
        UserDayTable.select(n).where { (UserDayTable.day greaterEq from) and (UserDayTable.day lessEq to) }.single()[n].toInt()
    }

    override suspend fun privateLeagueMembership(from: LocalDate, to: LocalDate): Pair<Int, Int> = dbQuery {
        query("""
            SELECT count(*) AS active,
              count(*) FILTER (WHERE EXISTS (
                SELECT 1 FROM clubs c JOIN league_members m ON m.club_id = c.id JOIN leagues l ON l.id = m.league_id
                WHERE c.user_id = a.user_id AND l.kind = 'PRIVATE')) AS in_private
            FROM (SELECT DISTINCT user_id FROM user_days WHERE day >= '$from' AND day <= '$to') a
        """.trimIndent()) { rs -> rs.next(); rs.getInt("active") to rs.getInt("in_private") }
    }

    override suspend fun invitedNewPlayers(from: Instant, to: Instant): Int = dbQuery {
        val n = InviteTable.inviteeClubId.countDistinct()
        InviteTable.select(n).where { (InviteTable.newPlayer eq true) and (InviteTable.createdAt greaterEq from) and (InviteTable.createdAt less to) }.single()[n].toInt()
    }

    override suspend fun purchases(from: Instant, to: Instant): Map<String, Int> = dbQuery {
        val n = PurchaseTable.id.count()
        PurchaseTable.select(PurchaseTable.productId, n)
            .where { (PurchaseTable.status eq "GRANTED") and (PurchaseTable.store neq "DEV") and (PurchaseTable.createdAt greaterEq from) and (PurchaseTable.createdAt less to) }
            .groupBy(PurchaseTable.productId).associate { it[PurchaseTable.productId] to it[n].toInt() }
    }

    override suspend fun adRewards(from: Instant, to: Instant): Int = dbQuery {
        AdRewardTable.selectAll().where { (AdRewardTable.createdAt greaterEq from) and (AdRewardTable.createdAt less to) }.count().toInt()
    }

    override suspend fun eventCounts(from: Instant, to: Instant, limit: Int): Map<String, Int> = dbQuery {
        val n = AnalyticsEventTable.id.count()
        AnalyticsEventTable.select(AnalyticsEventTable.name, n)
            .where { (AnalyticsEventTable.createdAt greaterEq from) and (AnalyticsEventTable.createdAt less to) }
            .groupBy(AnalyticsEventTable.name).orderBy(n, SortOrder.DESC).limit(limit)
            .associate { it[AnalyticsEventTable.name] to it[n].toInt() }
    }
}
