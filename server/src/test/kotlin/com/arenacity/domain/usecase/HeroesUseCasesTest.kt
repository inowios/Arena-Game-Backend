package com.arenacity.domain.usecase

import com.arenacity.domain.exception.InsufficientFundsException
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.HeroSource
import com.arenacity.domain.rules.BuildingType
import com.arenacity.domain.rules.Heroes
import com.arenacity.domain.rules.UnlockKind
import com.arenacity.domain.usecase.athlete.CardMatchReport
import com.arenacity.domain.usecase.athlete.CardMatchUseCases
import com.arenacity.domain.usecase.athlete.StarterSquadInitializer
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.club.CreateClubUseCase
import com.arenacity.domain.usecase.hero.HeroUseCases
import com.arenacity.domain.usecase.hero.StartHeroesInitializer
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class HeroesUseCasesTest {
    private val clock = FixedClock(Instant.parse("2026-10-16T10:00:00Z"))
    private val clubs = FakeClubRepository(); private val buildings = FakeBuildingRepository(); private val athletes = FakeAthleteRepository(); private val heroes = FakeHeroRepository()
    private val service = ClubService(clubs, buildings, clock)
    private val use = HeroUseCases(service, heroes, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock), StartHeroesInitializer(use)))
    private val cards = CardMatchUseCases(service, clubs, athletes, clock, heroes = use)
    private val u1 = UUID.randomUUID()

    private suspend fun gold(clubId: UUID, gold: Int) = clubs.update(clubs.findById(clubId)!!.copy(gold = gold))
    private fun stadium(clubId: UUID, level: Int) {
        val pitch = buildings.rows.values.first { it.clubId == clubId && it.type == BuildingType.PITCH }
        buildings.rows[pitch.id] = pitch.copy(level = level)
    }

    @Test
    fun `a new club owns the start six, everyone else is locked with its rule`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        assertEquals(Heroes.START.sorted(), heroes.listByClub(club.id).map { it.heroId })
        assertTrue(heroes.listByClub(club.id).all { it.level == 1 && it.xp == 0 && it.source == HeroSource.START })
        val r = use.roster(u1)
        assertEquals(51, r.heroes.size); assertEquals(1, r.stadium)
        assertEquals(Heroes.START.toSet(), r.heroes.filter { it.owned != null }.map { it.spec.id }.toSet())
        val mirai = r.heroes.single { it.spec.id == "p004" }
        assertNull(mirai.owned); assertEquals(UnlockKind.STORY, mirai.rule.kind); assertEquals(2, mirai.rule.chapter)
        assertTrue(mirai.available); assertFalse(mirai.canBuy)   // 50 gold at the start
        val beast = r.heroes.single { it.spec.id == "p012" }
        assertFalse(beast.available); assertFalse(beast.canBuy); assertEquals(3, beast.rule.stadium)
    }

    @Test
    fun `an older club gets the start six on first access, once`() = runBlocking<Unit> {
        val club = CreateClubUseCase(clubs, buildings, clock, emptyList())(u1, "Old Boys", "#AA0000", "#FFFFFF", "UTC")
        assertTrue(heroes.listByClub(club.id).isEmpty())
        assertEquals(6, use.roster(u1).heroes.count { it.owned != null })
        assertEquals(6, use.roster(u1).heroes.count { it.owned != null })
        assertEquals(6, heroes.rows.size)
    }

    @Test
    fun `a hero is bought for gold once, a people waits for its stadium`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        assertFailsWith<InsufficientFundsException> { use.unlock(u1, "p006") }   // Rei Kasumi, 500
        gold(club.id, 2000)
        val rei = use.unlock(u1, "p006")
        assertEquals(500, rei.spent); assertEquals(1500, rei.club.gold); assertEquals(1500, clubs.findById(club.id)!!.gold)
        assertEquals(HeroSource.CRYSTALS, rei.hero.owned!!.source); assertFalse(rei.hero.canBuy)
        // idempotent: owned already, nothing more is taken
        val again = use.unlock(u1, "p006")
        assertEquals(0, again.spent); assertEquals(1500, again.club.gold)
        assertEquals(0, use.unlock(u1, "p001").spent)   // a start hero
        // the beasts need stadium 3
        val e = assertFailsWith<ValidationException> { use.unlock(u1, "p018") }
        assertEquals("Requires stadium level 3", e.message)
        stadium(club.id, 3)
        assertTrue(use.roster(u1).heroes.single { it.spec.id == "p018" }.canBuy)
        assertEquals(300, use.unlock(u1, "p018").spent)   // Bryn Dask, common
        assertFailsWith<ValidationException> { use.unlock(u1, "p032") }   // androids: stadium 5
        assertFailsWith<NotFoundException> { use.unlock(u1, "p999") }
        assertEquals(1200, clubs.findById(club.id)!!.gold)
        assertEquals(8, use.roster(u1).heroes.count { it.owned != null })
    }

    @Test
    fun `heroes who played a card match earn experience by the result, capped a day`() = runBlocking<Unit> {
        create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        fun report(won: Boolean, vararg ids: String) = CardMatchReport(7, won = won, draw = false, goalsFor = if (won) 2 else 0, goalsAgainst = 1, cards = emptyMap(), heroes = ids.toList())
        val o = cards.report(u1, report(true, "p001", "p003", "p001", "p004", "p777"))
        assertEquals(50, o.heroXp)
        assertEquals(listOf("p001", "p003"), o.heroes.map { it.spec.id })   // not owned / unknown ids are skipped, duplicates count once
        assertEquals(50, o.heroes[0].owned!!.xp)
        cards.report(u1, report(true, "p001"))
        val kai = use.roster(u1).heroes.single { it.spec.id == "p001" }.owned!!
        assertEquals(2, kai.level); assertEquals(0, kai.xp)   // 100 → level 2
        assertEquals(20, cards.report(u1, report(false, "p001")).heroes.single().owned!!.xp)
        // the daily cap: 600 a hero
        repeat(20) { cards.report(u1, report(true, "p003")) }
        val itsuki = heroes.rows.values.single { it.heroId == "p003" }
        assertEquals(Heroes.MAX_MATCH_XP_PER_DAY, itsuki.xpToday)
        assertEquals(Heroes.grow(1, 0, Heroes.MAX_MATCH_XP_PER_DAY), itsuki.level to itsuki.xp)
        // the next UTC day it earns again
        clock.advanceSeconds(24 * 3600)
        assertEquals(50, cards.report(u1, report(true, "p003")).heroes.single().owned!!.xpToday)
        assertFailsWith<ValidationException> { cards.report(u1, report(true, *(1..11).map { "p%03d".format(it) }.toTypedArray())) }
        // a conceded match gives nothing
        assertEquals(0, cards.report(u1, CardMatchReport(1, false, false, 0, 3, emptyMap(), conceded = true, heroes = listOf("p001"))).heroXp)
    }
}
