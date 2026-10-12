package com.arenacity.domain.repository

import com.arenacity.domain.model.*
import java.util.UUID

interface LeagueRepository {
    suspend fun findById(id: UUID): League?
    /** SELECT … FOR UPDATE on the league row (membership changes). */
    suspend fun lockById(id: UUID): League?
    suspend fun findByCode(code: String): League?
    suspend fun listAll(kind: LeagueKind): List<League>
    suspend fun listForClub(clubId: UUID): List<League>
    /** Public league in the bucket/division that has not started (current_round == 0) and has fewer than [max] members. */
    suspend fun findOpenPublic(tzBucket: Int, division: Int, max: Int): League?
    suspend fun create(league: League): League
    suspend fun update(league: League): League
    suspend fun members(leagueId: UUID): List<UUID>
    /** Clubs that are members of any ACTIVE public league of this bucket and division (bots there are not free). */
    suspend fun activePublicMembers(tzBucket: Int, division: Int): Set<UUID>
    suspend fun addMember(leagueId: UUID, clubId: UUID)
    suspend fun removeMember(leagueId: UUID, clubId: UUID)
}

interface FixtureRepository {
    suspend fun createAll(fixtures: List<Fixture>)
    suspend fun findById(id: UUID): Fixture?
    suspend fun findByIds(ids: Collection<UUID>): List<Fixture>
    suspend fun listByLeagueSeason(leagueId: UUID, season: Int, round: Int? = null): List<Fixture>
    suspend fun listByClub(clubId: UUID, kind: FixtureKind? = null, limit: Int = 50): List<Fixture>
    /** Fixtures between two clubs (any kind), newest first. */
    suspend fun listBetween(a: UUID, b: UUID, limit: Int = 30): List<Fixture>
    suspend fun listPlayedByClubsSince(clubIds: Collection<UUID>, since: java.time.Instant): List<Fixture>
    suspend fun deleteScheduled(leagueId: UUID, season: Int)
    /** Marks a fixture played only if it is still SCHEDULED. Returns false when already played. */
    suspend fun markPlayed(fixture: Fixture): Boolean
}
