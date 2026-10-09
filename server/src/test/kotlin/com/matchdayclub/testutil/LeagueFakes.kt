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
    override suspend fun listBetween(a: UUID, b: UUID, limit: Int) = rows.values.filter { (it.homeClubId == a && it.awayClubId == b) || (it.homeClubId == b && it.awayClubId == a) }.sortedByDescending { it.scheduledAt }.take(limit)
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
    val adRewards = mutableSetOf<String>()
    override suspend fun recordAdReward(transactionId: String, clubId: UUID, placement: String, at: java.time.Instant) = adRewards.add(transactionId)
}

class FakeInventoryRepository : com.matchdayclub.domain.repository.InventoryRepository {
    val rows = mutableListOf<com.matchdayclub.domain.model.ClubItem>()
    override suspend fun items(clubId: UUID) = rows.filter { it.clubId == clubId }
    override suspend fun add(item: com.matchdayclub.domain.model.ClubItem): Boolean {
        if (rows.any { it.clubId == item.clubId && it.itemId == item.itemId }) return false
        rows += item; return true
    }
}

class FakeAchievementRepository : com.matchdayclub.domain.repository.AchievementRepository {
    val counters = mutableMapOf<Pair<UUID, com.matchdayclub.domain.rules.Metric>, Long>()
    val claims = mutableMapOf<Pair<UUID, String>, Int>()
    override suspend fun counters(clubId: UUID) = counters.filterKeys { it.first == clubId }.mapKeys { it.key.second }
    override suspend fun increment(clubId: UUID, metric: com.matchdayclub.domain.rules.Metric, by: Long) { counters.merge(clubId to metric, by, Long::plus) }
    override suspend fun raiseTo(clubId: UUID, metric: com.matchdayclub.domain.rules.Metric, value: Long) { counters.merge(clubId to metric, value) { a, b -> maxOf(a, b) } }
    override suspend fun claimed(clubId: UUID) = claims.filterKeys { it.first == clubId }.mapKeys { it.key.second }
    override suspend fun setClaimed(clubId: UUID, achievementId: String, tier: Int, at: java.time.Instant) { claims[clubId to achievementId] = tier }
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

class FakeChatRepository : com.matchdayclub.domain.repository.ChatRepository {
    val rows = mutableListOf<LeagueMessage>()
    val reports = mutableSetOf<Pair<UUID, UUID>>()
    val blocks = mutableSetOf<Pair<UUID, UUID>>()
    override suspend fun post(message: LeagueMessage) = message.also { rows += it }
    override suspend fun list(leagueId: UUID, before: Instant?, limit: Int) = rows.filter { it.leagueId == leagueId && !it.deleted && (before == null || it.createdAt.isBefore(before)) }.sortedByDescending { it.createdAt }.take(limit)
    override suspend fun since(leagueId: UUID, after: Instant, limit: Int) = rows.filter { it.leagueId == leagueId && !it.deleted && it.createdAt.isAfter(after) }.sortedBy { it.createdAt }.take(limit)
    override suspend fun find(id: UUID) = rows.firstOrNull { it.id == id }
    override suspend fun markDeleted(id: UUID) { val i = rows.indexOfFirst { it.id == id }; if (i >= 0) rows[i] = rows[i].copy(deleted = true) }
    override suspend fun countByClubSince(clubId: UUID, since: Instant) = rows.count { it.clubId == clubId && !it.createdAt.isBefore(since) }
    override suspend fun lastByClub(clubId: UUID) = rows.filter { it.clubId == clubId }.maxByOrNull { it.createdAt }
    override suspend fun report(messageId: UUID, reporterClubId: UUID, reason: String) = reports.add(messageId to reporterClubId)
    override suspend fun reportCount(messageId: UUID) = reports.count { it.first == messageId }
    override suspend fun block(clubId: UUID, blockedClubId: UUID) { blocks += clubId to blockedClubId }
    override suspend fun unblock(clubId: UUID, blockedClubId: UUID) { blocks -= clubId to blockedClubId }
    override suspend fun blockedBy(clubId: UUID) = blocks.filter { it.first == clubId }.map { it.second }.toSet()
}

class FakeSocialRepository : com.matchdayclub.domain.repository.SocialRepository {
    val seasons = mutableListOf<PrivateLeagueSeason>()
    val challenges = linkedMapOf<UUID, Challenge>()
    val invites = mutableListOf<Invite>()
    override suspend fun savePrivateSeason(s: PrivateLeagueSeason) = s.also { seasons += it }
    override suspend fun privateSeasons(leagueId: UUID) = seasons.filter { it.leagueId == leagueId }.sortedByDescending { it.seasonNumber }
    override suspend fun createChallenge(c: Challenge) = c.also { challenges[it.id] = it }
    override suspend fun challengeByCode(code: String) = challenges.values.firstOrNull { it.code == code }
    override suspend fun acceptChallenge(id: UUID, clubId: UUID, fixtureId: UUID): Boolean {
        val c = challenges[id] ?: return false
        if (c.status != ChallengeStatus.OPEN) return false
        challenges[id] = c.copy(status = ChallengeStatus.ACCEPTED, acceptedByClubId = clubId, fixtureId = fixtureId); return true
    }
    override suspend fun openChallengesOf(clubId: UUID, now: Instant) = challenges.values.filter { it.challengerClubId == clubId && it.status == ChallengeStatus.OPEN && it.expiresAt.isAfter(now) }
    override suspend fun recordInvite(i: Invite): Boolean {
        if (invites.any { it.inviteeClubId == i.inviteeClubId && it.kind == i.kind && it.code == i.code }) return false
        invites += i; return true
    }
    override suspend fun invitesBy(clubId: UUID) = invites.filter { it.inviterClubId == clubId }
}
