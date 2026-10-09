package com.matchdayclub.domain.repository

import com.matchdayclub.domain.model.*
import java.time.Instant
import java.util.UUID

interface ChatRepository {
    suspend fun post(message: LeagueMessage): LeagueMessage
    /** Newest first, older than [before] when given; deleted lines excluded. */
    suspend fun list(leagueId: UUID, before: Instant?, limit: Int): List<LeagueMessage>
    suspend fun since(leagueId: UUID, after: Instant, limit: Int): List<LeagueMessage>
    suspend fun find(id: UUID): LeagueMessage?
    suspend fun markDeleted(id: UUID)
    suspend fun countByClubSince(clubId: UUID, since: Instant): Int
    suspend fun lastByClub(clubId: UUID): LeagueMessage?
    suspend fun report(messageId: UUID, reporterClubId: UUID, reason: String): Boolean
    suspend fun reportCount(messageId: UUID): Int
    suspend fun block(clubId: UUID, blockedClubId: UUID)
    suspend fun unblock(clubId: UUID, blockedClubId: UUID)
    suspend fun blockedBy(clubId: UUID): Set<UUID>
}

interface SocialRepository {
    suspend fun savePrivateSeason(s: PrivateLeagueSeason): PrivateLeagueSeason
    suspend fun privateSeasons(leagueId: UUID): List<PrivateLeagueSeason>
    suspend fun createChallenge(c: Challenge): Challenge
    suspend fun challengeByCode(code: String): Challenge?
    /** Conditional OPEN → ACCEPTED; false when someone else accepted first. */
    suspend fun acceptChallenge(id: UUID, clubId: UUID, fixtureId: UUID): Boolean
    suspend fun openChallengesOf(clubId: UUID, now: Instant): List<Challenge>
    suspend fun recordInvite(i: Invite): Boolean
    suspend fun invitesBy(clubId: UUID): List<Invite>
}
