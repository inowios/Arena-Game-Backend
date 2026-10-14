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
    private val buildings = FakeBuildingRepository()
    private val repo = FakeLiveOpsRepository()
    private val service = ClubService(clubs, buildings, clock)
    private val cosmetics = CosmeticsService(FakeInventoryRepository(), clock)
    private val bought = mutableSetOf<String>()
    private val ops = LiveOps(service, clubs, repo, cosmetics, clock, purchases = { _, pid -> pid in bought })
    private val create = CreateClubUseCase(clubs, buildings, clock, emptyList())
    private val place = PlaceBuildingUseCase(service, buildings, clock)
    private val user = UUID.randomUUID()
    private fun club() = clubs.rows.values.first()

    init { service.effects = { ops.modifiers().let { EventEffects(it.buildTimePct, it.incomePct, it.obstacleGoldPct) } } }
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
    fun `a running event shortens constructions and its goal pays once`() = runBlocking<Unit> {
        setup()
        val b = place(user, BuildingType.ACADEMY, 9, 25, 0)
        val full = BuildingCatalog.level(BuildingType.ACADEMY, 1).buildSeconds
        assertEquals(clock.now().plusSeconds(full * 70 / 100), b.upgradeEndsAt)
        val ev = ops.list(user).first { it.event.key == "builders_day" }
        assertTrue(ev.running)
        assertFailsWith<ValidationException> { ops.claim(user, ev.event.id) }
        repeat(5) { ops.record(club().id, ProgressEvent.OBSTACLE_CLEARED) }
        assertEquals(3, ops.list(user).first { it.event.id == ev.event.id }.progress!!.count)   // capped at the target
        val gold = club().gold
        assertEquals(15, ops.claim(user, ev.event.id).gold)
        assertEquals(gold + 15, club().gold)
        assertFailsWith<ConflictException> { ops.claim(user, ev.event.id) }
    }

    @Test
    fun `an admin event doubles income on top of the atmosphere`() = runBlocking<Unit> {
        setup()
        ops.createEvent("double_income", clock.now().minusSeconds(60), clock.now().plusSeconds(3600), 100, 200, 100, null, 0, 0, 0, null)
        assertEquals(200, ops.modifiers().incomePct)
        val office = buildings.listByClub(club().id).first { it.type == BuildingType.TICKET_OFFICE }
        clock.advanceSeconds(3600)
        assertEquals(0, ops.modifiers().incomePct - 100)   // over
        clock.instant = clock.instant.minusSeconds(1800)
        val collect = CollectIncomeUseCase(service, clubs, buildings, clock)
        val bonus = Atmosphere.of(buildings.listByClub(club().id), 0, 100).getValue(office.id)
        assertEquals(bonus.total, Atmosphere.of(buildings.listByClub(club().id)).getValue(office.id).total + 100)
        assertTrue(collect(user, office.id).coins > 0)
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

    @Test
    fun `daily offers sell once a day, the starter pack shows until bought`() = runBlocking<Unit> {
        setup()
        val v = ops.offers(user)
        assertTrue(v.starterAvailable)
        assertEquals(3, v.daily.size)
        val pack = v.daily.first()
        val gold = club().gold; val coins = club().coins
        ops.buyOffer(user, pack.id)
        assertEquals(gold - pack.gold, club().gold); assertEquals(coins + pack.coins, club().coins)
        assertFailsWith<ConflictException> { ops.buyOffer(user, pack.id) }
        bought += LiveOps.STARTER_PACK
        assertFalse(ops.offers(user).starterAvailable)
        clock.advanceSeconds(86_400)
        assertFalse(ops.offers(user).daily.first().bought)   // a new day, new offers
    }
}

class FakeLiveOpsRepository : LiveOpsRepository {
    val events = mutableListOf<GameEvent>(); val prog = mutableMapOf<Pair<UUID, String>, EventProgress>()
    val mails = mutableListOf<DevMail>(); val mailClaims = mutableSetOf<Pair<UUID, UUID>>(); val offers = mutableSetOf<Triple<UUID, Int, String>>()
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
    override suspend fun offerBought(clubId: UUID, day: Int, offerId: String) = Triple(clubId, day, offerId) in offers
    override suspend fun markOffer(clubId: UUID, day: Int, offerId: String, at: Instant) = offers.add(Triple(clubId, day, offerId))
}
