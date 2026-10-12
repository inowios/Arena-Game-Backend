package com.arenacity.data.repository

import com.arenacity.common.extension.dbQuery
import com.arenacity.data.database.*
import com.arenacity.domain.model.*
import com.arenacity.domain.repository.ChatRepository
import com.arenacity.domain.repository.SocialRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import java.time.Instant
import java.util.UUID

class ChatRepositoryImpl : ChatRepository {
    override suspend fun post(message: LeagueMessage) = dbQuery {
        LeagueMessageTable.insert { it[id] = message.id; it[leagueId] = message.leagueId; it[clubId] = message.clubId; it[kind] = message.kind.name; it[text] = message.text; it[deleted] = message.deleted; it[createdAt] = message.createdAt }
        message
    }
    override suspend fun list(leagueId: UUID, before: Instant?, limit: Int) = dbQuery {
        var cond: Op<Boolean> = (LeagueMessageTable.leagueId eq leagueId) and (LeagueMessageTable.deleted eq false)
        if (before != null) cond = cond and (LeagueMessageTable.createdAt less before)
        LeagueMessageTable.selectAll().where { cond }.orderBy(LeagueMessageTable.createdAt, SortOrder.DESC).limit(limit).map { it.toMessage() }
    }
    override suspend fun since(leagueId: UUID, after: Instant, limit: Int) = dbQuery {
        LeagueMessageTable.selectAll().where { (LeagueMessageTable.leagueId eq leagueId) and (LeagueMessageTable.deleted eq false) and (LeagueMessageTable.createdAt greater after) }
            .orderBy(LeagueMessageTable.createdAt, SortOrder.ASC).limit(limit).map { it.toMessage() }
    }
    override suspend fun find(id: UUID) = dbQuery { LeagueMessageTable.selectAll().where { LeagueMessageTable.id eq id }.singleOrNull()?.toMessage() }
    override suspend fun markDeleted(id: UUID) { dbQuery { LeagueMessageTable.update({ LeagueMessageTable.id eq id }) { it[deleted] = true } } }
    override suspend fun countByClubSince(clubId: UUID, since: Instant) = dbQuery {
        LeagueMessageTable.selectAll().where { (LeagueMessageTable.clubId eq clubId) and (LeagueMessageTable.createdAt greaterEq since) }.count().toInt()
    }
    override suspend fun lastByClub(clubId: UUID) = dbQuery {
        LeagueMessageTable.selectAll().where { LeagueMessageTable.clubId eq clubId }.orderBy(LeagueMessageTable.createdAt, SortOrder.DESC).limit(1).singleOrNull()?.toMessage()
    }
    override suspend fun report(messageId: UUID, reporterClubId: UUID, reason: String) = dbQuery {
        MessageReportTable.insertIgnore { it[id] = UUID.randomUUID(); it[this.messageId] = messageId; it[this.reporterClubId] = reporterClubId; it[this.reason] = reason; it[createdAt] = Instant.now() }.insertedCount > 0
    }
    override suspend fun reportCount(messageId: UUID) = dbQuery { MessageReportTable.selectAll().where { MessageReportTable.messageId eq messageId }.count().toInt() }
    override suspend fun block(clubId: UUID, blockedClubId: UUID) { dbQuery { ClubBlockTable.insertIgnore { it[this.clubId] = clubId; it[this.blockedClubId] = blockedClubId; it[createdAt] = Instant.now() } } }
    override suspend fun unblock(clubId: UUID, blockedClubId: UUID) { dbQuery { ClubBlockTable.deleteWhere { (ClubBlockTable.clubId eq clubId) and (ClubBlockTable.blockedClubId eq blockedClubId) } } }
    override suspend fun blockedBy(clubId: UUID) = dbQuery { ClubBlockTable.selectAll().where { ClubBlockTable.clubId eq clubId }.map { it[ClubBlockTable.blockedClubId] }.toSet() }

    private fun ResultRow.toMessage() = LeagueMessage(this[LeagueMessageTable.id], this[LeagueMessageTable.leagueId], this[LeagueMessageTable.clubId], MessageKind.valueOf(this[LeagueMessageTable.kind]), this[LeagueMessageTable.text], this[LeagueMessageTable.deleted], this[LeagueMessageTable.createdAt])
}

class SocialRepositoryImpl : SocialRepository {
    override suspend fun savePrivateSeason(s: PrivateLeagueSeason) = dbQuery {
        PrivateLeagueSeasonTable.insertIgnore { it[id] = s.id; it[leagueId] = s.leagueId; it[seasonNumber] = s.seasonNumber; it[winnerClubId] = s.winnerClubId; it[tableJson] = s.tableJson; it[endedAt] = s.endedAt }
        s
    }
    override suspend fun privateSeasons(leagueId: UUID) = dbQuery {
        PrivateLeagueSeasonTable.selectAll().where { PrivateLeagueSeasonTable.leagueId eq leagueId }.orderBy(PrivateLeagueSeasonTable.seasonNumber, SortOrder.DESC).map {
            PrivateLeagueSeason(it[PrivateLeagueSeasonTable.id], it[PrivateLeagueSeasonTable.leagueId], it[PrivateLeagueSeasonTable.seasonNumber], it[PrivateLeagueSeasonTable.winnerClubId], it[PrivateLeagueSeasonTable.tableJson], it[PrivateLeagueSeasonTable.endedAt])
        }
    }
    override suspend fun createChallenge(c: Challenge) = dbQuery {
        ChallengeTable.insert { it[id] = c.id; it[code] = c.code; it[challengerClubId] = c.challengerClubId; it[acceptedByClubId] = c.acceptedByClubId; it[fixtureId] = c.fixtureId; it[status] = c.status.name; it[expiresAt] = c.expiresAt; it[createdAt] = c.createdAt }
        c
    }
    override suspend fun challengeByCode(code: String) = dbQuery { ChallengeTable.selectAll().where { ChallengeTable.code eq code }.singleOrNull()?.toChallenge() }
    override suspend fun acceptChallenge(id: UUID, clubId: UUID, fixtureId: UUID) = dbQuery {
        ChallengeTable.update({ (ChallengeTable.id eq id) and (ChallengeTable.status eq ChallengeStatus.OPEN.name) }) { it[status] = ChallengeStatus.ACCEPTED.name; it[acceptedByClubId] = clubId; it[this.fixtureId] = fixtureId } == 1
    }
    override suspend fun openChallengesOf(clubId: UUID, now: Instant) = dbQuery {
        ChallengeTable.selectAll().where { (ChallengeTable.challengerClubId eq clubId) and (ChallengeTable.status eq ChallengeStatus.OPEN.name) and (ChallengeTable.expiresAt greater now) }.map { it.toChallenge() }
    }
    override suspend fun recordInvite(i: Invite) = dbQuery {
        InviteTable.insertIgnore { it[id] = i.id; it[inviterClubId] = i.inviterClubId; it[inviteeClubId] = i.inviteeClubId; it[kind] = i.kind.name; it[code] = i.code; it[newPlayer] = i.newPlayer; it[createdAt] = i.createdAt }.insertedCount > 0
    }
    override suspend fun invitesBy(clubId: UUID) = dbQuery {
        InviteTable.selectAll().where { InviteTable.inviterClubId eq clubId }.map { Invite(it[InviteTable.id], it[InviteTable.inviterClubId], it[InviteTable.inviteeClubId], InviteKind.valueOf(it[InviteTable.kind]), it[InviteTable.code], it[InviteTable.newPlayer], it[InviteTable.createdAt]) }
    }

    private fun ResultRow.toChallenge() = Challenge(this[ChallengeTable.id], this[ChallengeTable.code], this[ChallengeTable.challengerClubId], this[ChallengeTable.acceptedByClubId], this[ChallengeTable.fixtureId], ChallengeStatus.valueOf(this[ChallengeTable.status]), this[ChallengeTable.expiresAt], this[ChallengeTable.createdAt])
}
