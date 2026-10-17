package com.arenacity.data.repository

import com.arenacity.common.extension.dbQuery
import com.arenacity.data.database.*
import com.arenacity.domain.usecase.alliance.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import java.time.Instant
import java.util.UUID

class AllianceRepositoryImpl : AllianceRepository {
    private fun ResultRow.alliance() = Alliance(this[AllianceTable.id], this[AllianceTable.name], this[AllianceTable.tag], this[AllianceTable.description], this[AllianceTable.emblemJson],
        this[AllianceTable.open], this[AllianceTable.minTrophies], this[AllianceTable.createdBy], this[AllianceTable.createdAt], this[AllianceTable.updatedAt])
    private fun ResultRow.member() = AllianceMember(this[AllianceMemberTable.allianceId], this[AllianceMemberTable.clubId], AllianceRole.valueOf(this[AllianceMemberTable.role]),
        this[AllianceMemberTable.joinedAt], this[AllianceMemberTable.helpsGiven])

    override suspend fun create(a: Alliance) = dbQuery {
        AllianceTable.insert { it[id] = a.id; it[name] = a.name; it[tag] = a.tag; it[description] = a.description; it[emblemJson] = a.emblemJson; it[open] = a.open
            it[minTrophies] = a.minTrophies; it[createdBy] = a.createdBy; it[createdAt] = a.createdAt; it[updatedAt] = a.updatedAt }
        a
    }
    override suspend fun update(a: Alliance) = dbQuery {
        AllianceTable.update({ AllianceTable.id eq a.id }) { it[description] = a.description; it[emblemJson] = a.emblemJson; it[open] = a.open; it[minTrophies] = a.minTrophies; it[updatedAt] = a.updatedAt }
        a
    }
    override suspend fun delete(id: UUID) { dbQuery { LeagueMessageTable.deleteWhere { LeagueMessageTable.leagueId eq id }; AllianceTable.deleteWhere { AllianceTable.id eq id } } }
    override suspend fun find(id: UUID) = dbQuery { AllianceTable.selectAll().where { AllianceTable.id eq id }.singleOrNull()?.alliance() }
    override suspend fun findByName(name: String) = dbQuery { AllianceTable.selectAll().where { AllianceTable.name.lowerCase() eq name.lowercase() }.singleOrNull()?.alliance() }
    override suspend fun search(query: String?, limit: Int) = dbQuery {
        val q = AllianceTable.selectAll()
        if (query != null) q.where { (AllianceTable.name.lowerCase() like "%${query.lowercase().replace("%", "")}%") or (AllianceTable.tag eq query.uppercase()) }
        q.orderBy(AllianceTable.open to SortOrder.DESC, AllianceTable.updatedAt to SortOrder.DESC).limit(limit).map { it.alliance() }
    }
    override suspend fun memberCounts(ids: Collection<UUID>): Map<UUID, Int> = if (ids.isEmpty()) emptyMap() else dbQuery {
        val c = AllianceMemberTable.clubId.count()
        AllianceMemberTable.select(AllianceMemberTable.allianceId, c).where { AllianceMemberTable.allianceId inList ids }.groupBy(AllianceMemberTable.allianceId)
            .associate { it[AllianceMemberTable.allianceId] to it[c].toInt() }
    }

    override suspend fun members(allianceId: UUID) = dbQuery { AllianceMemberTable.selectAll().where { AllianceMemberTable.allianceId eq allianceId }.map { it.member() } }
    override suspend fun membership(clubId: UUID) = dbQuery { AllianceMemberTable.selectAll().where { AllianceMemberTable.clubId eq clubId }.singleOrNull()?.member() }
    override suspend fun addMember(m: AllianceMember) = dbQuery {
        AllianceMemberTable.insertIgnore { it[allianceId] = m.allianceId; it[clubId] = m.clubId; it[role] = m.role.name; it[joinedAt] = m.joinedAt; it[helpsGiven] = m.helpsGiven }.insertedCount == 1
    }
    override suspend fun updateMember(m: AllianceMember) { dbQuery { AllianceMemberTable.update({ (AllianceMemberTable.allianceId eq m.allianceId) and (AllianceMemberTable.clubId eq m.clubId) }) { it[role] = m.role.name; it[helpsGiven] = m.helpsGiven } } }
    override suspend fun removeMember(allianceId: UUID, clubId: UUID) { dbQuery { AllianceMemberTable.deleteWhere { (AllianceMemberTable.allianceId eq allianceId) and (AllianceMemberTable.clubId eq clubId) } } }

    override suspend fun requests(allianceId: UUID) = dbQuery {
        AllianceRequestTable.selectAll().where { AllianceRequestTable.allianceId eq allianceId }.orderBy(AllianceRequestTable.createdAt)
            .map { JoinRequest(it[AllianceRequestTable.allianceId], it[AllianceRequestTable.clubId], it[AllianceRequestTable.message], it[AllianceRequestTable.createdAt]) }
    }
    override suspend fun addRequest(r: JoinRequest) = dbQuery {
        AllianceRequestTable.insertIgnore { it[allianceId] = r.allianceId; it[clubId] = r.clubId; it[message] = r.message; it[createdAt] = r.createdAt }.insertedCount == 1
    }
    override suspend fun removeRequest(allianceId: UUID, clubId: UUID) { dbQuery { AllianceRequestTable.deleteWhere { (AllianceRequestTable.allianceId eq allianceId) and (AllianceRequestTable.clubId eq clubId) } } }
    override suspend fun clearRequests(clubId: UUID) { dbQuery { AllianceRequestTable.deleteWhere { AllianceRequestTable.clubId eq clubId } } }

    override suspend fun addWeekendWin(week: Int, allianceId: UUID, clubId: UUID) {
        dbQuery {
            val n = AllianceWeekendTable.update({ (AllianceWeekendTable.week eq week) and (AllianceWeekendTable.allianceId eq allianceId) and (AllianceWeekendTable.clubId eq clubId) }) {
                it.update(wins, wins + 1)
            }
            if (n == 0) AllianceWeekendTable.insertIgnore { it[this.week] = week; it[this.allianceId] = allianceId; it[this.clubId] = clubId; it[wins] = 1 }
        }
    }
    override suspend fun weekendTable(week: Int, limit: Int) = dbQuery {
        val s = AllianceWeekendTable.wins.sum()
        AllianceWeekendTable.select(AllianceWeekendTable.allianceId, s).where { AllianceWeekendTable.week eq week }.groupBy(AllianceWeekendTable.allianceId)
            .orderBy(s, SortOrder.DESC).limit(limit).map { it[AllianceWeekendTable.allianceId] to (it[s] ?: 0) }
    }
    override suspend fun weekendWins(week: Int, allianceId: UUID) = dbQuery {
        AllianceWeekendTable.selectAll().where { (AllianceWeekendTable.week eq week) and (AllianceWeekendTable.allianceId eq allianceId) }.associate { it[AllianceWeekendTable.clubId] to it[AllianceWeekendTable.wins] }
    }
    override suspend fun claimWeekend(week: Int, clubId: UUID, at: Instant) = dbQuery {
        AllianceWeekendClaimTable.insertIgnore { it[this.week] = week; it[this.clubId] = clubId; it[claimedAt] = at }.insertedCount == 1
    }

    override suspend fun like(clubId: UUID, liker: UUID, week: Int, at: Instant) = dbQuery {
        ClubLikeTable.insertIgnore { it[this.clubId] = clubId; it[likerId] = liker; it[this.week] = week; it[createdAt] = at }.insertedCount == 1
    }
    override suspend fun likes(clubId: UUID, week: Int) = dbQuery { ClubLikeTable.selectAll().where { (ClubLikeTable.clubId eq clubId) and (ClubLikeTable.week eq week) }.count().toInt() }
    override suspend fun liked(clubId: UUID, liker: UUID, week: Int) = dbQuery {
        ClubLikeTable.selectAll().where { (ClubLikeTable.clubId eq clubId) and (ClubLikeTable.likerId eq liker) and (ClubLikeTable.week eq week) }.count() > 0
    }
    override suspend fun topLiked(week: Int, limit: Int) = dbQuery {
        val c = ClubLikeTable.likerId.count()
        ClubLikeTable.select(ClubLikeTable.clubId, c).where { ClubLikeTable.week eq week }.groupBy(ClubLikeTable.clubId).orderBy(c, SortOrder.DESC).limit(limit)
            .map { it[ClubLikeTable.clubId] to it[c].toInt() }
    }
}
