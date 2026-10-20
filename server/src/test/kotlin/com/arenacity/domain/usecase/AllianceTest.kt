package com.arenacity.domain.usecase

import com.arenacity.domain.exception.*
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.alliance.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.time.Instant
import java.util.UUID

class AllianceTest {
    private val clock = FixedClock(Instant.parse("2026-10-10T12:00:00Z"))   // a Saturday
    private val clubs = FakeClubRepository()
    private val repo = FakeAllianceRepository()
    private val spots = FakeClubSpotRepository()
    private val service = ClubService(clubs, clock, spots)
    private val create = CreateClubUseCase(clubs, clock, emptyList())
    private val alliances = AllianceUseCases(service, clubs, repo, clock)
    private val a = UUID.randomUUID(); private val b = UUID.randomUUID(); private val c = UUID.randomUUID()

    private fun club(u: UUID) = clubs.rows.values.first { it.userId == u }
    private fun setup() = runBlocking {
        for ((u, n) in listOf(a to "Alpha_FC", b to "Beta_FC", c to "Gamma_FC")) {
            create(u, n, "#AA0000", "#FFFFFF", "UTC"); clubs.update(club(u).copy(coins = 50_000, gold = 100))
        }
        spots.open(club(a).id, MapSpots.ACADEMY)   // stadium 2: one spot open
    }

    @Test
    fun `a club with a stadium 2 founds an alliance, others join an open one and wait at a closed one`() = runBlocking<Unit> {
        setup()
        assertFailsWith<ValidationException> { alliances.create(b, "Beta Union", "BU", "", true, 0, null) }   // stadium 1
        val v = alliances.create(a, "Northern Lights", "NL", "hello", true, 0, null)
        assertEquals(AllianceRole.LEADER, v.me?.role)
        assertEquals(50_000 - AllianceRules.CREATE_COINS, club(a).coins)
        assertFailsWith<ConflictException> { alliances.create(a, "Second", "SE", "", true, 0, null) }
        assertNotNull(alliances.join(b, v.alliance.id))
        assertEquals(2, alliances.mine(a)!!.members.size)
        alliances.update(a, null, false, null, null)
        assertNull(alliances.join(c, v.alliance.id))                     // closed: a request
        assertEquals(1, alliances.mine(a)!!.requests.size)
        assertFailsWith<AuthorizationException> { alliances.answer(b, club(c).id, true) }   // a member cannot accept
        alliances.answer(a, club(c).id, true)
        assertEquals(3, alliances.mine(c)!!.members.size)
    }

    @Test
    fun `roles promote demote and kick, the leader leaving hands over, the last one closes the alliance`() = runBlocking<Unit> {
        setup()
        val id = alliances.create(a, "Northern Lights", "NL", "", true, 0, null).alliance.id
        alliances.join(b, id); alliances.join(c, id)
        alliances.manage(a, club(b).id, "promote"); alliances.manage(a, club(b).id, "promote")
        assertEquals(AllianceRole.CO_LEADER, repo.membership(club(b).id)!!.role)
        assertFailsWith<AuthorizationException> { alliances.manage(b, club(a).id, "kick") }
        alliances.manage(b, club(c).id, "kick")
        assertNull(repo.membership(club(c).id))
        alliances.leave(a)
        assertEquals(AllianceRole.LEADER, repo.membership(club(b).id)!!.role)
        alliances.leave(b)
        assertNull(repo.find(id))
    }

    @Test
    fun `weekend arena wins add up per alliance and the reward is claimed once after the weekend`() = runBlocking<Unit> {
        setup()
        val id = alliances.create(a, "Northern Lights", "NL", "", true, 0, null).alliance.id
        alliances.join(b, id)
        repeat(4) { alliances.recordWin(club(a).id) }
        alliances.recordWin(club(b).id)
        alliances.recordWin(club(c).id)   // no alliance: not counted
        val live = alliances.tournament(a)
        assertTrue(live.live); assertEquals(5, live.mine!!.wins); assertEquals(1, live.mine!!.place); assertEquals(4, live.myWins)
        assertNull(live.claimable)
        clock.advanceSeconds(2 * 86_400)   // Monday
        val after = alliances.tournament(a)
        assertFalse(after.live)
        val r = alliances.claimWeekend(a)
        assertEquals(AllianceRules.weekendGold(1), r.gold); assertEquals(4 * AllianceRules.WIN_COINS, r.coins)
        assertFailsWith<ConflictException> { alliances.claimWeekend(a) }
        assertFailsWith<ConflictException> { alliances.claimWeekend(b) }   // 1 win: below the minimum
        // weekday wins do not count
        alliances.recordWin(club(a).id)
        assertEquals(5, repo.weekendWins(AllianceRules.week(Instant.parse("2026-10-10T12:00:00Z")), id).values.sum())
    }

    @Test
    fun `likes one per club per week, the most liked lead the week`() = runBlocking<Unit> {
        setup()
        assertFailsWith<ValidationException> { alliances.like(a, club(a).id) }
        assertEquals(1, alliances.like(a, club(b).id))
        assertFailsWith<ConflictException> { alliances.like(a, club(b).id) }
        alliances.like(c, club(b).id); alliances.like(b, club(c).id)
        assertEquals(listOf("Beta_FC", "Gamma_FC"), alliances.topLiked().map { it.first.name })
        assertTrue(alliances.likeState(a, club(b).id).likedByMe)
    }
}

class FakeAllianceRepository : AllianceRepository {
    val alliances = linkedMapOf<UUID, Alliance>(); val members = mutableListOf<AllianceMember>(); val reqs = mutableListOf<JoinRequest>()
    val weekend = mutableMapOf<Triple<Int, UUID, UUID>, Int>(); val claims = mutableSetOf<Pair<Int, UUID>>(); val likes = mutableSetOf<Triple<UUID, UUID, Int>>()
    override suspend fun create(a: Alliance) = a.also { alliances[a.id] = a }
    override suspend fun update(a: Alliance) = a.also { alliances[a.id] = a }
    override suspend fun delete(id: UUID) { alliances.remove(id); members.removeAll { it.allianceId == id } }
    override suspend fun find(id: UUID) = alliances[id]
    override suspend fun findByName(name: String) = alliances.values.firstOrNull { it.name.equals(name, true) }
    override suspend fun search(query: String?, limit: Int) = alliances.values.filter { query == null || it.name.contains(query, true) }.take(limit)
    override suspend fun memberCounts(ids: Collection<UUID>) = ids.associateWith { id -> members.count { it.allianceId == id } }
    override suspend fun members(allianceId: UUID) = members.filter { it.allianceId == allianceId }
    override suspend fun membership(clubId: UUID) = members.firstOrNull { it.clubId == clubId }
    override suspend fun addMember(m: AllianceMember) = if (members.any { it.clubId == m.clubId }) false else members.add(m)
    override suspend fun updateMember(m: AllianceMember) { members.replaceAll { if (it.clubId == m.clubId && it.allianceId == m.allianceId) m else it } }
    override suspend fun removeMember(allianceId: UUID, clubId: UUID) { members.removeAll { it.allianceId == allianceId && it.clubId == clubId } }
    override suspend fun requests(allianceId: UUID) = reqs.filter { it.allianceId == allianceId }
    override suspend fun addRequest(r: JoinRequest) = if (reqs.any { it.allianceId == r.allianceId && it.clubId == r.clubId }) false else reqs.add(r)
    override suspend fun removeRequest(allianceId: UUID, clubId: UUID) { reqs.removeAll { it.allianceId == allianceId && it.clubId == clubId } }
    override suspend fun clearRequests(clubId: UUID) { reqs.removeAll { it.clubId == clubId } }
    override suspend fun addWeekendWin(week: Int, allianceId: UUID, clubId: UUID) { weekend.merge(Triple(week, allianceId, clubId), 1, Int::plus) }
    override suspend fun weekendTable(week: Int, limit: Int) = weekend.filterKeys { it.first == week }.entries.groupBy { it.key.second }.map { (id, e) -> id to e.sumOf { it.value } }.sortedByDescending { it.second }.take(limit)
    override suspend fun weekendWins(week: Int, allianceId: UUID) = weekend.filterKeys { it.first == week && it.second == allianceId }.mapKeys { it.key.third }
    override suspend fun claimWeekend(week: Int, clubId: UUID, at: Instant) = claims.add(week to clubId)
    override suspend fun like(clubId: UUID, liker: UUID, week: Int, at: Instant) = likes.add(Triple(clubId, liker, week))
    override suspend fun likes(clubId: UUID, week: Int) = likes.count { it.first == clubId && it.third == week }
    override suspend fun liked(clubId: UUID, liker: UUID, week: Int) = Triple(clubId, liker, week) in likes
    override suspend fun topLiked(week: Int, limit: Int) = likes.filter { it.third == week }.groupBy { it.first }.map { it.key to it.value.size }.sortedByDescending { it.second }.take(limit)
}
