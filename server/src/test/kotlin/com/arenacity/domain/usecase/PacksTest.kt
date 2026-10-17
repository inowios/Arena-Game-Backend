package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.CardKind
import com.arenacity.domain.model.PackOpen
import com.arenacity.domain.repository.PackRepository
import com.arenacity.domain.rules.Packs
import com.arenacity.domain.rules.PullTier
import com.arenacity.domain.usecase.athlete.PackUseCases
import com.arenacity.domain.usecase.athlete.StarterSquadInitializer
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.club.CreateClubUseCase
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.random.Random
import kotlin.test.*

class FakePackRepository : PackRepository {
    val rows = ArrayList<PackOpen>()
    override suspend fun listByClub(clubId: UUID, limit: Int) = rows.filter { it.clubId == clubId }.sortedByDescending { it.createdAt }.take(limit)
    override suspend fun create(open: PackOpen): PackOpen { rows += open; return open }
}

class PacksTest {
    private val clock = FixedClock(Instant.parse("2026-10-11T10:00:00Z"))
    private val clubs = FakeClubRepository(); private val athletes = FakeAthleteRepository(); private val packs = FakePackRepository()
    private val service = ClubService(clubs, clock)
    private val create = CreateClubUseCase(clubs, clock, listOf(StarterSquadInitializer(athletes, clock)))
    private val use = PackUseCases(service, clubs, athletes, packs, clock)
    private val u1 = UUID.randomUUID()

    @Test
    fun `the basic pack is free once a day, then costs coins, and better kinds wait for the club level`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val before = athletes.listByClub(club.id).size
        val shelf = use.shelf(u1)
        assertTrue(shelf.offers.first { it.spec.kind == CardKind.CLASSIC }.freeToday)
        assertTrue(shelf.offers.first { it.spec.kind == CardKind.CLASSIC }.open)
        assertFalse(shelf.offers.first { it.spec.kind == CardKind.LEGEND }.open)
        val r1 = use.open(u1, CardKind.CLASSIC)
        assertEquals(Packs.CARDS_PER_PACK, r1.athletes.size); assertEquals(club.coins, r1.club.coins)
        assertTrue(r1.athletes.all { it.cardKind == CardKind.CLASSIC && it.clubId == club.id })
        assertEquals(before + 3, athletes.listByClub(club.id).size)
        assertFalse(use.shelf(u1).offers.first { it.spec.kind == CardKind.CLASSIC }.freeToday)
        clubs.update(clubs.findById(club.id)!!.copy(coins = 5000))
        val r2 = use.open(u1, CardKind.CLASSIC)
        assertEquals(5000 - Packs.of(CardKind.CLASSIC)!!.coins, r2.club.coins)
        clubs.update(clubs.findById(club.id)!!.copy(coins = 10))
        assertFailsWith<ValidationException> { use.open(u1, CardKind.CLASSIC) }
        assertFailsWith<ValidationException> { use.open(u1, CardKind.LEGEND) }
        clubs.update(clubs.findById(club.id)!!.copy(level = 12, gold = 480))
        val r3 = use.open(u1, CardKind.LEGEND)
        assertEquals(0, r3.club.gold); assertTrue(r3.athletes.all { it.cardKind == CardKind.LEGEND })
        // the quality follows the tier
        r3.athletes.zip(r3.open.tiers).forEach { (a, t) -> val q = a.attributeAverage; assertTrue(q in (t.quality.first - 8)..(t.quality.last + 8), "$t $q") }
    }

    @Test
    fun `the pity guarantees an epic within 30 cards and a legend within 120`() {
        val spec = Packs.of(CardKind.CLASSIC)!!
        val r = Random(7)
        // a cold streak: with the pity counters at the edge the roll is forced
        assertEquals(PullTier.EPIC, Packs.roll(spec, r, sinceEpic = Packs.EPIC_PITY - 1, sinceLegend = 0))
        assertEquals(PullTier.LEGEND, Packs.roll(spec, r, sinceEpic = 0, sinceLegend = Packs.LEGEND_PITY - 1))
        // over many rolls the public chances hold roughly
        val n = 20000; val counts = IntArray(4)
        repeat(n) { counts[Packs.roll(spec, r, 0, 0).ordinal]++ }
        assertTrue(counts[0] in (n * 0.67).toInt()..(n * 0.73).toInt(), "common ${counts[0]}")
        assertTrue(counts[3] in (n * 0.01).toInt()..(n * 0.02).toInt(), "legend ${counts[3]}")
    }

    @Test
    fun `the collection has a ceiling`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val have = athletes.listByClub(club.id).size
        val filler = (1..(Packs.COLLECTION_MAX - have - 2)).map { athletes.listByClub(club.id).first().copy(id = UUID.randomUUID()) }
        athletes.createAll(filler)
        assertFailsWith<ConflictException> { use.open(u1, CardKind.CLASSIC) }
    }
}
