package com.matchdayclub.data.repository

import com.matchdayclub.common.extension.dbQuery
import com.matchdayclub.data.database.FixtureTable
import com.matchdayclub.data.database.LeagueMemberTable
import com.matchdayclub.data.database.LeagueTable
import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.repository.FixtureRepository
import com.matchdayclub.domain.repository.LeagueRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import java.time.Instant
import java.util.UUID

class LeagueRepositoryImpl : LeagueRepository {
    override suspend fun findById(id: UUID) = dbQuery { LeagueTable.selectAll().where { LeagueTable.id eq id }.singleOrNull()?.toLeague() }
    override suspend fun lockById(id: UUID) = dbQuery { LeagueTable.selectAll().where { LeagueTable.id eq id }.forUpdate().singleOrNull()?.toLeague() }
    override suspend fun findByCode(code: String) = dbQuery { LeagueTable.selectAll().where { LeagueTable.code eq code }.singleOrNull()?.toLeague() }
    override suspend fun listAll(kind: LeagueKind) = dbQuery { LeagueTable.selectAll().where { (LeagueTable.kind eq kind.name) and (LeagueTable.status eq LeagueStatus.ACTIVE.name) }.map { it.toLeague() } }
    override suspend fun listForClub(clubId: UUID) = dbQuery {
        (LeagueTable innerJoin LeagueMemberTable).selectAll().where { LeagueMemberTable.clubId eq clubId }.map { it.toLeague() }
    }
    override suspend fun findOpenPublic(tzBucket: Int, division: Int, max: Int): League? = dbQuery {
        LeagueTable.selectAll().where { (LeagueTable.kind eq LeagueKind.PUBLIC.name) and (LeagueTable.tzBucket eq tzBucket) and (LeagueTable.division eq division) and (LeagueTable.currentRound eq 0) and (LeagueTable.status eq LeagueStatus.ACTIVE.name) }
            .orderBy(LeagueTable.createdAt).map { it.toLeague() }
            .firstOrNull { l -> LeagueMemberTable.selectAll().where { LeagueMemberTable.leagueId eq l.id }.count() < max }
    }
    override suspend fun create(league: League) = dbQuery { LeagueTable.insert { it.fill(league); it[id] = league.id; it[kind] = league.kind.name; it[createdBy] = league.createdBy; it[createdAt] = league.createdAt }; league }
    override suspend fun update(league: League) = dbQuery { LeagueTable.update({ LeagueTable.id eq league.id }) { it.fill(league) }; league }
    override suspend fun members(leagueId: UUID) = dbQuery { LeagueMemberTable.selectAll().where { LeagueMemberTable.leagueId eq leagueId }.orderBy(LeagueMemberTable.joinedAt).map { it[LeagueMemberTable.clubId] } }
    override suspend fun activePublicMembers(tzBucket: Int, division: Int): Set<UUID> = dbQuery {
        (LeagueMemberTable innerJoin LeagueTable).select(LeagueMemberTable.clubId)
            .where { (LeagueTable.kind eq LeagueKind.PUBLIC.name) and (LeagueTable.status eq LeagueStatus.ACTIVE.name) and (LeagueTable.tzBucket eq tzBucket) and (LeagueTable.division eq division) }
            .map { it[LeagueMemberTable.clubId] }.toSet()
    }
    override suspend fun addMember(leagueId: UUID, clubId: UUID) { dbQuery { LeagueMemberTable.insertIgnore { it[this.leagueId] = leagueId; it[this.clubId] = clubId; it[joinedAt] = Instant.now() } } }
    override suspend fun removeMember(leagueId: UUID, clubId: UUID) { dbQuery { LeagueMemberTable.deleteWhere { (LeagueMemberTable.leagueId eq leagueId) and (LeagueMemberTable.clubId eq clubId) } } }

    private fun UpdateBuilder<*>.fill(l: League) {
        this[LeagueTable.name] = l.name; this[LeagueTable.code] = l.code; this[LeagueTable.division] = l.division; this[LeagueTable.tzBucket] = l.tzBucket
        this[LeagueTable.timezone] = l.timezone; this[LeagueTable.matchdayHour] = l.matchdayHour; this[LeagueTable.seasonNumber] = l.seasonNumber
        this[LeagueTable.seasonStart] = l.seasonStart; this[LeagueTable.currentRound] = l.currentRound; this[LeagueTable.updatedAt] = l.updatedAt
        this[LeagueTable.status] = l.status.name
        this[LeagueTable.createdBy] = l.createdBy
    }

    private fun ResultRow.toLeague() = League(
        this[LeagueTable.id], LeagueKind.valueOf(this[LeagueTable.kind]), this[LeagueTable.name], this[LeagueTable.code], this[LeagueTable.division], this[LeagueTable.tzBucket],
        this[LeagueTable.timezone], this[LeagueTable.matchdayHour], this[LeagueTable.seasonNumber], this[LeagueTable.seasonStart], this[LeagueTable.currentRound],
        this[LeagueTable.createdBy], this[LeagueTable.createdAt], this[LeagueTable.updatedAt], LeagueStatus.valueOf(this[LeagueTable.status]),
    )
}

class FixtureRepositoryImpl : FixtureRepository {
    override suspend fun createAll(fixtures: List<Fixture>) {
        dbQuery {
            FixtureTable.batchInsert(fixtures) { f ->
                this[FixtureTable.id] = f.id; this[FixtureTable.leagueId] = f.leagueId; this[FixtureTable.kind] = f.kind.name; this[FixtureTable.seasonNumber] = f.seasonNumber
                this[FixtureTable.round] = f.round; this[FixtureTable.scheduledAt] = f.scheduledAt; this[FixtureTable.homeClubId] = f.homeClubId; this[FixtureTable.awayClubId] = f.awayClubId
                this[FixtureTable.status] = f.status.name; this[FixtureTable.homeScore] = f.homeScore; this[FixtureTable.awayScore] = f.awayScore; this[FixtureTable.seed] = f.seed
                this[FixtureTable.resultJson] = f.resultJson; this[FixtureTable.playedAt] = f.playedAt; this[FixtureTable.createdAt] = f.createdAt
                this[FixtureTable.homePoints] = f.homePoints; this[FixtureTable.awayPoints] = f.awayPoints
            }
        }
    }
    override suspend fun findById(id: UUID) = dbQuery { FixtureTable.selectAll().where { FixtureTable.id eq id }.singleOrNull()?.toFixture() }
    override suspend fun findByIds(ids: Collection<UUID>) = if (ids.isEmpty()) emptyList() else dbQuery { FixtureTable.selectAll().where { FixtureTable.id inList ids }.map { it.toFixture() } }
    override suspend fun listByLeagueSeason(leagueId: UUID, season: Int, round: Int?) = dbQuery {
        var cond: Op<Boolean> = (FixtureTable.leagueId eq leagueId) and (FixtureTable.seasonNumber eq season)
        if (round != null) cond = cond and (FixtureTable.round eq round)
        FixtureTable.selectAll().where { cond }.orderBy(FixtureTable.round).map { it.toFixture() }
    }
    override suspend fun listByClub(clubId: UUID, kind: FixtureKind?, limit: Int) = dbQuery {
        var cond: Op<Boolean> = (FixtureTable.homeClubId eq clubId) or (FixtureTable.awayClubId eq clubId)
        if (kind != null) cond = cond and (FixtureTable.kind eq kind.name)
        FixtureTable.selectAll().where { cond }.orderBy(FixtureTable.scheduledAt, SortOrder.DESC).limit(limit).map { it.toFixture() }
    }
    override suspend fun listBetween(a: UUID, b: UUID, limit: Int) = dbQuery {
        FixtureTable.selectAll().where { ((FixtureTable.homeClubId eq a) and (FixtureTable.awayClubId eq b)) or ((FixtureTable.homeClubId eq b) and (FixtureTable.awayClubId eq a)) }
            .orderBy(FixtureTable.scheduledAt, SortOrder.DESC).limit(limit).map { it.toFixture() }
    }
    override suspend fun listPlayedByClubsSince(clubIds: Collection<UUID>, since: Instant) = if (clubIds.isEmpty()) emptyList() else dbQuery {
        FixtureTable.selectAll().where { ((FixtureTable.homeClubId inList clubIds) or (FixtureTable.awayClubId inList clubIds)) and (FixtureTable.kind eq FixtureKind.LEAGUE.name) and (FixtureTable.status eq FixtureStatus.PLAYED.name) and (FixtureTable.playedAt greaterEq since) }.map { it.toFixture() }
    }
    override suspend fun deleteScheduled(leagueId: UUID, season: Int) {
        dbQuery { FixtureTable.deleteWhere { (FixtureTable.leagueId eq leagueId) and (FixtureTable.seasonNumber eq season) and (FixtureTable.status eq FixtureStatus.SCHEDULED.name) } }
    }

    override suspend fun markPlayed(fixture: Fixture): Boolean = dbQuery {
        FixtureTable.update({ (FixtureTable.id eq fixture.id) and (FixtureTable.status eq FixtureStatus.SCHEDULED.name) }) {
            it[status] = FixtureStatus.PLAYED.name; it[homeScore] = fixture.homeScore; it[awayScore] = fixture.awayScore; it[resultJson] = fixture.resultJson; it[playedAt] = fixture.playedAt
            it[homePoints] = fixture.homePoints; it[awayPoints] = fixture.awayPoints
        } == 1
    }

    private fun ResultRow.toFixture() = Fixture(
        this[FixtureTable.id], this[FixtureTable.leagueId], FixtureKind.valueOf(this[FixtureTable.kind]), this[FixtureTable.seasonNumber], this[FixtureTable.round], this[FixtureTable.scheduledAt],
        this[FixtureTable.homeClubId], this[FixtureTable.awayClubId], FixtureStatus.valueOf(this[FixtureTable.status]), this[FixtureTable.homeScore], this[FixtureTable.awayScore],
        this[FixtureTable.seed], this[FixtureTable.resultJson], this[FixtureTable.playedAt], this[FixtureTable.createdAt],
        this[FixtureTable.homePoints], this[FixtureTable.awayPoints],
    )
}
