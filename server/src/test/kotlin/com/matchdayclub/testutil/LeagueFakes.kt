package com.matchdayclub.testutil

import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.repository.FixtureRepository
import com.matchdayclub.domain.repository.LeagueRepository
import java.time.Instant
import java.util.UUID

class FakeLeagueRepository : LeagueRepository {
    val rows = linkedMapOf<UUID, League>()
    val members = linkedMapOf<UUID, MutableList<UUID>>()
    override suspend fun findById(id: UUID) = rows[id]
    override suspend fun lockById(id: UUID) = rows[id]
    override suspend fun findByCode(code: String) = rows.values.firstOrNull { it.code == code }
    override suspend fun listAll(kind: LeagueKind) = rows.values.filter { it.kind == kind && it.status == LeagueStatus.ACTIVE }
    override suspend fun listForClub(clubId: UUID) = rows.values.filter { members[it.id]?.contains(clubId) == true }
    override suspend fun findOpenPublic(tzBucket: Int, division: Int, max: Int) = rows.values.firstOrNull { it.kind == LeagueKind.PUBLIC && it.status == LeagueStatus.ACTIVE && it.tzBucket == tzBucket && it.division == division && it.currentRound == 0 && (members[it.id]?.size ?: 0) < max }
    override suspend fun create(league: League): League { rows[league.id] = league; members[league.id] = mutableListOf(); return league }
    override suspend fun update(league: League): League { rows[league.id] = league; return league }
    override suspend fun members(leagueId: UUID) = members[leagueId]?.toList() ?: emptyList()
    override suspend fun activePublicMembers(tzBucket: Int, division: Int) =
        rows.values.filter { it.kind == LeagueKind.PUBLIC && it.status == LeagueStatus.ACTIVE && it.tzBucket == tzBucket && it.division == division }.flatMap { members[it.id] ?: emptyList() }.toSet()
    override suspend fun addMember(leagueId: UUID, clubId: UUID) { members.getOrPut(leagueId) { mutableListOf() }.let { if (clubId !in it) it += clubId } }
    override suspend fun removeMember(leagueId: UUID, clubId: UUID) { members[leagueId]?.remove(clubId) }
}

class FakeFixtureRepository : FixtureRepository {
    val rows = linkedMapOf<UUID, Fixture>()
    override suspend fun createAll(fixtures: List<Fixture>) { fixtures.forEach { rows[it.id] = it } }
    override suspend fun findById(id: UUID) = rows[id]
    override suspend fun listByLeagueSeason(leagueId: UUID, season: Int, round: Int?) = rows.values.filter { it.leagueId == leagueId && it.seasonNumber == season && (round == null || it.round == round) }.sortedBy { it.round }
    override suspend fun listByClub(clubId: UUID, kind: FixtureKind?, limit: Int) = rows.values.filter { it.involves(clubId) && (kind == null || it.kind == kind) }.sortedByDescending { it.scheduledAt }.take(limit)
    override suspend fun listPlayedByClubsSince(clubIds: Collection<UUID>, since: Instant) = rows.values.filter { f -> f.kind == FixtureKind.LEAGUE && f.status == FixtureStatus.PLAYED && f.playedAt != null && !f.playedAt.isBefore(since) && clubIds.any { f.involves(it) } }
    override suspend fun deleteScheduled(leagueId: UUID, season: Int) { rows.values.filter { it.leagueId == leagueId && it.seasonNumber == season && it.status == FixtureStatus.SCHEDULED }.map { it.id }.forEach { rows.remove(it) } }
    override suspend fun markPlayed(fixture: Fixture): Boolean {
        val cur = rows[fixture.id] ?: return false
        if (cur.status != FixtureStatus.SCHEDULED) return false
        rows[fixture.id] = fixture; return true
    }
}

class FakeEconomyRepository : com.matchdayclub.domain.repository.EconomyRepository {
    val passes = mutableMapOf<Pair<UUID, Int>, com.matchdayclub.domain.model.SeasonPass>()
    val tasks = mutableMapOf<Triple<UUID, java.time.LocalDate, String>, com.matchdayclub.domain.model.DailyTask>()
    val purchases = mutableMapOf<String, com.matchdayclub.domain.model.Purchase>()
    override suspend fun pass(clubId: UUID, season: Int) = passes[clubId to season]
    override suspend fun upsertPass(pass: com.matchdayclub.domain.model.SeasonPass) = pass.also { passes[it.clubId to it.season] = it }
    override suspend fun tasks(clubId: UUID, day: java.time.LocalDate) = tasks.values.filter { it.clubId == clubId && it.day == day }
    override suspend fun upsertTask(task: com.matchdayclub.domain.model.DailyTask) = task.also { tasks[Triple(it.clubId, it.day, it.task)] = it }
    override suspend fun purchaseByExternalId(externalId: String) = purchases[externalId]
    override suspend fun createPurchase(p: com.matchdayclub.domain.model.Purchase) = p.also { purchases[it.externalId] = it }
}

class FakeSeasonRepository : com.matchdayclub.domain.repository.SeasonRepository {
    val seasons = mutableListOf<com.matchdayclub.domain.model.ClubSeason>()
    val trophies = mutableListOf<com.matchdayclub.domain.model.Trophy>()
    override suspend fun createSeason(season: com.matchdayclub.domain.model.ClubSeason) = season.also { seasons += it }
    override suspend fun seasonsOf(clubId: UUID, limit: Int) = seasons.filter { it.clubId == clubId }.sortedByDescending { it.finishedAt }.take(limit)
    override suspend fun seasonFor(clubId: UUID, leagueId: UUID) = seasons.firstOrNull { it.clubId == clubId && it.leagueId == leagueId }
    override suspend fun createTrophy(trophy: com.matchdayclub.domain.model.Trophy) = trophy.also { trophies += it }
    override suspend fun trophiesOf(clubId: UUID) = trophies.filter { it.clubId == clubId }
}
