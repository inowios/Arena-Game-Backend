package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.InsufficientFundsException
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.HeroSource
import com.arenacity.domain.rules.Heroes
import com.arenacity.domain.rules.UnlockKind
import com.arenacity.domain.usecase.arena.ArenaReport
import com.arenacity.domain.usecase.arena.ArenaMatchUseCases
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
    private val clubs = FakeClubRepository(); private val spots = FakeClubSpotRepository(); private val athletes = FakeAthleteRepository(); private val heroes = FakeHeroRepository()
    private val service = ClubService(clubs, clock, spots)
    private val use = HeroUseCases(service, heroes, clock)
    private val create = CreateClubUseCase(clubs, clock, listOf(StarterSquadInitializer(athletes, clock), StartHeroesInitializer(use)))
    private val cards = ArenaMatchUseCases(service, clubs, FakeArenaMatchRepository(), clock, heroes = use)
    private val u1 = UUID.randomUUID()

    private suspend fun gold(clubId: UUID, gold: Int) = clubs.update(clubs.findById(clubId)!!.copy(gold = gold))
    private fun stadium(clubId: UUID, level: Int) = spots.stadium(clubId, level)

    @Test
    fun `a new club owns the start six, everyone else is locked with its rule`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        assertEquals(Heroes.START.sorted(), heroes.listByClub(club.id).map { it.heroId })
        assertTrue(heroes.listByClub(club.id).all { it.level == 1 && it.xp == 0 && it.source == HeroSource.START })
        val r = use.roster(u1)
        assertEquals(51, r.heroes.size); assertEquals(1, r.stadium)
        assertEquals(Heroes.START.toSet(), r.heroes.filter { it.owned != null }.map { it.spec.id }.toSet())
        val shizuku = r.heroes.single { it.spec.id == "p010" }
        assertNull(shizuku.owned); assertEquals(UnlockKind.STORY, shizuku.rule.kind); assertEquals(2, shizuku.rule.chapter)
        assertTrue(shizuku.available); assertFalse(shizuku.canBuy)   // 50 gold at the start
        val beast = r.heroes.single { it.spec.id == "p012" }
        assertFalse(beast.available); assertFalse(beast.canBuy); assertEquals(4, beast.rule.stadium); assertEquals("SOON", beast.reason)
        assertNull(shizuku.reason); assertNull(r.heroes.single { it.spec.id == "p001" }.reason)
    }

    @Test
    fun `an older club gets the start six on first access, once`() = runBlocking<Unit> {
        val club = CreateClubUseCase(clubs, clock, emptyList())(u1, "Old Boys", "#AA0000", "#FFFFFF", "UTC")
        assertTrue(heroes.listByClub(club.id).isEmpty())
        assertEquals(6, use.roster(u1).heroes.count { it.owned != null })
        assertEquals(6, use.roster(u1).heroes.count { it.owned != null })
        assertEquals(6, heroes.rows.size)
    }

    @Test
    fun `a hero is bought for gold once, a people's hero waits for its Arena kit`() = runBlocking<Unit> {
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
        // the beasts need stadium 4 (their island's bridge) — and an Arena kit, which they have not got yet: «незабаром»
        val e = assertFailsWith<ValidationException> { use.unlock(u1, "p018") }
        assertEquals("Coming soon", e.template); assertEquals("Незабаром", e.localized("uk"))
        stadium(club.id, 4)
        val bryn = use.roster(u1).heroes.single { it.spec.id == "p018" }
        assertFalse(bryn.available); assertFalse(bryn.canBuy); assertEquals("SOON", bryn.reason)
        assertEquals("Coming soon", assertFailsWith<ValidationException> { use.unlock(u1, "p018") }.template)
        assertFailsWith<ValidationException> { use.unlock(u1, "p032") }   // androids: no kit either
        assertFailsWith<NotFoundException> { use.unlock(u1, "p999") }
        assertEquals(1500, clubs.findById(club.id)!!.gold)
        assertEquals(7, use.roster(u1).heroes.count { it.owned != null })
    }

    @Test
    fun `heroes who played an Arena match earn experience by the result, capped a day`() = runBlocking<Unit> {
        create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        suspend fun report(won: Boolean, vararg ids: String): ArenaReport {
            clubs.update(clubs.findByUserId(u1)!!.copy(energy = 30, energyAt = null))   // energy is not the point here
            return ArenaReport(cards.start(u1).match.id, won = won, heroes = ids.toList())
        }
        val o = cards.report(u1, report(true, "p001", "p003", "p001", "p010"))
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
        assertFailsWith<ValidationException> { cards.report(u1, report(true, "p001", "p003", "p007", "p009")) }   // a triple at most
        // a conceded match gives nothing
        assertEquals(0, cards.report(u1, report(false, "p001").copy(conceded = true)).heroXp)
    }

    @Test
    fun `the Arena triple is saved when it is valid, none means the match picks`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val none = use.lineup(u1)
        assertFalse(none.saved); assertTrue(none.heroes.isEmpty()); assertNull(none.updatedAt)
        val saved = use.saveLineup(u1, listOf("p001", "p011", "p007"))   // Hikaru is an ordinary pick
        assertTrue(saved.saved); assertEquals(listOf("p001", "p011", "p007"), saved.heroes)
        assertEquals(saved, use.lineup(u1))
        assertEquals(listOf("p001", "p011", "p007"), heroes.lineups.getValue(club.id).heroes)
        // a shorter triple is fine (the match fills it); blanks are dropped
        assertEquals(listOf("p001"), use.saveLineup(u1, listOf("p001", " ")).heroes)
        val notMine = assertFailsWith<ValidationException> { use.saveLineup(u1, listOf("p010")) }
        assertEquals("Hero {0} is not in your club", notMine.template); assertEquals(listOf<Any?>("p010"), notMine.args)
        assertFailsWith<ValidationException> { use.saveLineup(u1, listOf("p001", "p001")) }
        assertFailsWith<ValidationException> { use.saveLineup(u1, listOf("p001", "p003", "p007", "p009")) }
        assertEquals(listOf("p001"), use.lineup(u1).heroes)   // the bad ones changed nothing
        // a hero bought later can join it
        gold(club.id, 1000); use.unlock(u1, "p006")
        assertEquals(listOf("p006", "p001", "p003"), use.saveLineup(u1, listOf("p006", "p001", "p003")).heroes)
    }

    @Test
    fun `a hero trains at the stadium's level for coins and gets its experience after the timer`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        clubs.update(clubs.findById(club.id)!!.copy(coins = 50_000, gold = 500))   // stadium 1 trains (the five islands map)
        val coins = clubs.findById(club.id)!!.coins
        val st = use.trainingStatus(u1)
        assertEquals(1, st.level); assertEquals(1, st.slots); assertEquals(200L, st.coins); assertEquals(80, st.xp); assertEquals(7200L, st.seconds)
        val t = use.train(u1, "p001")
        assertEquals(200L, t.spent); assertEquals(coins - 200, t.club.coins); assertEquals(coins - 200, clubs.findById(club.id)!!.coins)
        assertEquals(80, t.hero.owned!!.trainingXp); assertEquals(7200L, t.hero.trainingLeft)
        assertEquals(listOf("p001"), t.status.training.map { it.spec.id })
        assertFailsWith<ConflictException> { use.train(u1, "p001") }     // already training
        assertFailsWith<ConflictException> { use.train(u1, "p003") }     // one slot at level 1
        assertFailsWith<ValidationException> { use.train(u1, "p010") }   // not owned
        assertFailsWith<NotFoundException> { use.train(u1, "p999") }
        assertFailsWith<ConflictException> { use.claimTraining(u1, "p003") }   // not training
        clock.advanceSeconds(3600)
        assertEquals(3600L, use.roster(u1).heroes.single { it.spec.id == "p001" }.trainingLeft)
        assertEquals("Training still in progress", assertFailsWith<ConflictException> { use.claimTraining(u1, "p001") }.template)
        clock.advanceSeconds(3600)
        assertEquals(0L, use.trainingStatus(u1).training.single().trainingLeft)
        val c = use.claimTraining(u1, "p001")
        assertEquals(80, c.gained); assertEquals(1, c.levelBefore); assertEquals(1, c.hero.owned!!.level); assertEquals(80, c.hero.owned!!.xp)
        assertNull(c.hero.owned!!.trainingEndsAt); assertTrue(c.status.training.isEmpty())
        assertFailsWith<ConflictException> { use.claimTraining(u1, "p001") }   // claimed once
        // training ignores the match day cap: a hero that earned 600 in matches today still grows
        val kai = heroes.rows.values.single { it.clubId == club.id && it.heroId == "p001" }
        heroes.update(kai.copy(xpDay = java.time.LocalDate.of(2026, 10, 16), xpToday = Heroes.MAX_MATCH_XP_PER_DAY))
        use.train(u1, "p001"); clock.advanceSeconds(Heroes.TRAINING_SECONDS); val again = use.claimTraining(u1, "p001")
        assertEquals(80, again.gained); assertEquals(2 to 60, again.hero.owned!!.level to again.hero.owned!!.xp)   // 160 → level 2 + 60
        // the top level: nothing to train; one step below, the rest past level 10 is dropped
        heroes.update(heroes.rows.values.single { it.clubId == club.id && it.heroId == "p003" }.copy(level = 10, xp = 0))
        assertEquals("The hero is at the top level", assertFailsWith<ValidationException> { use.train(u1, "p003") }.template)
        heroes.update(heroes.rows.values.single { it.clubId == club.id && it.heroId == "p007" }.copy(level = 9, xp = 990))
        use.train(u1, "p007"); clock.advanceSeconds(Heroes.TRAINING_SECONDS)
        val top = use.claimTraining(u1, "p007")
        assertEquals(10, top.gained); assertEquals(10 to 0, top.hero.owned!!.level to top.hero.owned!!.xp)
        // not enough coins
        clubs.update(clubs.findById(club.id)!!.copy(coins = 100))
        assertFailsWith<InsufficientFundsException> { use.train(u1, "p009") }
        assertNull(heroes.rows.values.single { it.clubId == club.id && it.heroId == "p009" }.trainingEndsAt)
    }
}
