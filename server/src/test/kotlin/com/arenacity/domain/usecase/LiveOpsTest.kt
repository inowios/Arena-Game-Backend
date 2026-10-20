package com.arenacity.domain.usecase

import com.arenacity.domain.exception.*
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.domain.usecase.economy.CosmeticsService
import com.arenacity.domain.usecase.liveops.*
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.time.Instant
import java.util.UUID

class LiveOpsTest {
    private val clock = FixedClock(Instant.parse("2026-10-14T10:00:00Z"))   // a Wednesday
    private val clubs = FakeClubRepository()
    private val repo = FakeLiveOpsRepository()
    private val service = ClubService(clubs, clock)
    private val cosmetics = CosmeticsService(FakeInventoryRepository(), clock)
    private val ops = LiveOps(service, clubs, repo, cosmetics, clock)
    private val create = CreateClubUseCase(clubs, clock, emptyList())
    private val user = UUID.randomUUID()
    private fun club() = clubs.rows.values.first()

    private fun setup() = runBlocking { create(user, "Ops FC", "#AA0000", "#FFFFFF", "UTC"); clubs.update(club().copy(coins = 100_000, gold = 500)) }

    @Test
    fun `the calendar runs a builders' Wednesday, weekends, Halloween and the New Year`() {
        val wed = EventCalendar.between(Instant.parse("2026-10-14T10:00:00Z"), Instant.parse("2026-10-14T10:00:01Z"))
        assertEquals(listOf("builders_day"), wed.map { it.key })
        assertEquals(listOf("city_weekend"), EventCalendar.between(Instant.parse("2026-10-17T10:00:00Z"), Instant.parse("2026-10-17T10:00:01Z")).map { it.key })
        assertTrue(EventCalendar.between(Instant.parse("2026-10-30T10:00:00Z"), Instant.parse("2026-10-30T10:00:01Z")).any { it.key == "halloween" })
        assertTrue(EventCalendar.between(Instant.parse("2027-01-02T10:00:00Z"), Instant.parse("2027-01-02T10:00:01Z")).any { it.key == "new_year" })
    }

    @Test
    fun `a running event's goal pays once`() = runBlocking<Unit> {
        setup()
        val ev = ops.list(user).first { it.event.key == "builders_day" }
        assertTrue(ev.running)
        assertFailsWith<ValidationException> { ops.claim(user, ev.event.id) }
        // trainings since the income collections went with the city builder: three to start
        assertEquals(ProgressEvent.TRAINING_STARTED, ev.event.goal)
        repeat(5) { ops.record(club().id, ProgressEvent.TRAINING_STARTED) }
        assertEquals(3, ops.list(user).first { it.event.id == ev.event.id }.progress!!.count)   // capped at the target
        val gold = club().gold
        assertEquals(15, ops.claim(user, ev.event.id).gold)
        assertEquals(gold + 15, club().gold)
        assertFailsWith<ConflictException> { ops.claim(user, ev.event.id) }
    }

    @Test
    fun `an admin event runs for its time with its goal`() = runBlocking<Unit> {
        setup()
        ops.createEvent("goals", clock.now().minusSeconds(60), clock.now().plusSeconds(3600), 100, 200, 100, "KNOCKOUT", 2, 0, 10, null)
        val ev = ops.list(user).first { it.event.key == "goals" }
        assertEquals(ProgressEvent.KNOCKOUT, ev.event.goal); assertTrue(ev.running)
        repeat(2) { ops.record(club().id, ProgressEvent.KNOCKOUT) }
        assertEquals(10, ops.claim(user, ev.event.id).gold)
        assertFailsWith<ValidationException> { ops.createEvent("old", clock.now(), clock.now().plusSeconds(60), 100, 100, 100, "INCOME_COLLECTED", 1, 0, 0, null) }
    }

    @Test
    fun `developer mail gifts are claimed once, personal mail only by its club`() = runBlocking<Unit> {
        setup()
        val all = ops.sendMail("Welcome", "Thanks for playing", 1_000, 20, "decor_bench", null, 7)
        ops.sendMail("Sorry", "For you only", 0, 50, null, UUID.randomUUID(), 7)
        val list = ops.mail(user)
        assertEquals(listOf("Welcome"), list.map { it.mail.title })
        val r = ops.claimMail(user, all.id)
        assertEquals(1_000, r.coins); assertEquals("decor_bench", r.item)
        assertFailsWith<ConflictException> { ops.claimMail(user, all.id) }
        assertTrue(ops.mail(user).single().claimed)
    }
}

class FakeLiveOpsRepository : LiveOpsRepository {
    val events = mutableListOf<GameEvent>(); val prog = mutableMapOf<Pair<UUID, String>, EventProgress>()
    val mails = mutableListOf<DevMail>(); val mailClaims = mutableSetOf<Pair<UUID, UUID>>()
    override suspend fun adminEvents() = events.toList()
    override suspend fun createEvent(e: GameEvent) { events += e }
    override suspend fun deleteEvent(id: String) { events.removeAll { it.id == id } }
    override suspend fun progress(clubId: UUID, ids: Collection<String>) = ids.mapNotNull { prog[clubId to it] }.associateBy { it.eventId }
    override suspend fun bump(clubId: UUID, eventId: String, cap: Int) {
        val p = prog[clubId to eventId] ?: EventProgress(eventId, 0, false)
        if (p.count < cap) prog[clubId to eventId] = p.copy(count = p.count + 1)
    }
    override suspend fun claimEvent(clubId: UUID, eventId: String, at: Instant): Boolean {
        val p = prog[clubId to eventId] ?: return false
        if (p.claimed) return false; prog[clubId to eventId] = p.copy(claimed = true); return true
    }
    override suspend fun mails(clubId: UUID, now: Instant) = mails.filter { it.expiresAt.isAfter(now) && (it.clubId == null || it.clubId == clubId) }
    override suspend fun claimedMails(clubId: UUID) = mailClaims.filter { it.second == clubId }.map { it.first }.toSet()
    override suspend fun createMail(m: DevMail) { mails += m }
    override suspend fun claimMail(clubId: UUID, mailId: UUID, at: Instant) = mailClaims.add(mailId to clubId)
}
