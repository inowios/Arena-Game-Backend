package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.model.HeroSource
import com.arenacity.domain.model.StoryProgress
import com.arenacity.domain.rules.ChapterState
import com.arenacity.domain.rules.Story
import com.arenacity.domain.usecase.arena.ArenaReport
import com.arenacity.domain.usecase.arena.ArenaMatchUseCases
import com.arenacity.domain.usecase.athlete.StarterSquadInitializer
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.club.CreateClubUseCase
import com.arenacity.domain.usecase.hero.HeroUseCases
import com.arenacity.domain.usecase.hero.StartHeroesInitializer
import com.arenacity.domain.usecase.story.StoryUseCases
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class StoryUseCasesTest {
    private val clock = FixedClock(Instant.parse("2026-10-17T10:00:00Z"))
    private val clubs = FakeClubRepository(); private val spots = FakeClubSpotRepository(); private val athletes = FakeAthleteRepository(); private val heroes = FakeHeroRepository()
    private val stories = FakeStoryRepository()
    private val service = ClubService(clubs, clock, spots)
    private val heroUse = HeroUseCases(service, heroes, clock)
    private val story = StoryUseCases(service, clubs, stories, heroUse, clock)
    private val create = CreateClubUseCase(clubs, clock, listOf(StarterSquadInitializer(athletes, clock), StartHeroesInitializer(heroUse)))
    private val cards = ArenaMatchUseCases(service, clubs, FakeArenaMatchRepository(), clock, heroes = heroUse, stories = story)
    private val u1 = UUID.randomUUID()

    /** Starts a match (as the chapter's story match when [story] is given) and reports it; [gf]/[ga] are the old shape, ignored. */
    private suspend fun match(won: Boolean, gf: Int, ga: Int, vararg heroes: String, story: Int? = null): ArenaReport {
        clubs.update(clubs.findByUserId(u1)!!.copy(energy = 30, energyAt = null))   // energy is not the point here
        return ArenaReport(cards.start(u1, 1, story).match.id, won, heroes = heroes.toList())
    }

    @Test
    fun `a new club starts chapter 1, the rest locked`() = runBlocking<Unit> {
        create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val s = story.story(u1)
        assertEquals(1, s.current); assertFalse(s.finished)
        assertEquals(listOf(ChapterState.ACTIVE) + List(5) { ChapterState.LOCKED }, s.chapters.map { it.state })
        assertTrue(s.chapters[0].chapter.objectives.all { s.chapters[0].progressOf(it.id) == 0 })
        assertTrue(stories.rows.isEmpty())   // nothing stored until a match counts
    }

    @Test
    fun `Arena matches move chapter 1, claiming it opens chapter 2 and pays`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        assertFailsWith<ConflictException> { story.claim(u1, 1) }   // nothing done yet
        // a won match with Kai and Itsuki: the pair played once of twice, 1 of 2 wins
        val o1 = cards.report(u1, match(true, 2, 1, "p001", "p003", "p011"))
        assertTrue(o1.story!!.completed.isEmpty()); assertFalse(o1.story!!.chapterDone)
        assertEquals(1, o1.story!!.chapter.progressOf("c1.o1")); assertEquals(1, o1.story!!.chapter.progressOf("c1.o2"))
        // a match cannot be started as another chapter's story match; a hero id the club does not own does not count
        assertEquals("Not your current chapter", assertFailsWith<com.arenacity.domain.exception.ValidationException> { match(true, 1, 0, "p010", story = 2) }.template)
        assertEquals(listOf("c1.o2"), cards.report(u1, match(true, 1, 0, "p010")).story!!.completed)
        assertEquals(0, story.story(u1).chapters[0].progressOf("c1.o3"))
        assertEquals(2, story.story(u1).chapters[0].progressOf("c1.o2"))
        // the chapter's story match, won with the pair: the last two goals at once
        val o3 = cards.report(u1, match(true, 1, 0, "p001", "p003", story = 1))
        assertEquals(listOf("c1.o1", "c1.o3"), o3.story!!.completed); assertTrue(o3.story!!.chapterDone)
        assertEquals(ChapterState.DONE, story.story(u1).chapters[0].state)
        // more matches change nothing while it waits to be claimed
        assertTrue(cards.report(u1, match(true, 3, 0, "p001")).story!!.completed.isEmpty())
        assertFailsWith<ConflictException> { story.claim(u1, 2) }   // not open yet
        assertFailsWith<NotFoundException> { story.claim(u1, 9) }
        val before = clubs.findById(club.id)!!
        val r = story.claim(u1, 1)
        assertNull(r.hero)   // chapter 1 has no hero
        assertEquals(500L, r.coins); assertEquals(5, r.gems)
        assertEquals(before.coins + 500, clubs.findById(club.id)!!.coins); assertEquals(before.gold + 5, clubs.findById(club.id)!!.gold)
        assertEquals(2, r.story.current)
        assertEquals(listOf(ChapterState.CLAIMED, ChapterState.ACTIVE, ChapterState.LOCKED), r.story.chapters.take(3).map { it.state })
        assertFailsWith<ConflictException> { story.claim(u1, 1) }   // claimed once
        // chapter 2 starts from zero: the earlier matches do not count for it
        assertTrue(r.story.chapters[1].progress.values.all { it == 0 })
    }

    @Test
    fun `claiming chapter 2 gives Shizuku, a story hero bought before gets experience instead`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val c2 = Story.chapter(2)!!
        stories.save(StoryProgress(club.id, 2, c2.objectives.associate { it.id to it.target }, clock.now()))
        val r = story.claim(u1, 2)
        assertTrue(r.hero!!.joined); assertEquals("p010", r.hero!!.hero.spec.id)
        assertEquals(HeroSource.STORY, heroes.listByClub(club.id).single { it.heroId == "p010" }.source)
        assertEquals(3, r.story.current)
        // chapter 3's hero Raiga bought for gems first: claiming gives him experience, the coins and gems as usual
        clubs.update(clubs.findById(club.id)!!.copy(gold = 2000))
        heroUse.unlock(u1, "p005")
        val c3 = Story.chapter(3)!!
        stories.save(StoryProgress(club.id, 3, c3.objectives.associate { it.id to it.target }, clock.now()))
        val gold = clubs.findById(club.id)!!.gold
        val r3 = story.claim(u1, 3)
        assertFalse(r3.hero!!.joined); assertEquals(Story.OWNED_HERO_XP, r3.hero!!.xp)
        val raiga = heroes.listByClub(club.id).single { it.heroId == "p005" }
        assertEquals(HeroSource.CRYSTALS, raiga.source); assertEquals(3, raiga.level)   // 250 xp: 100 + 150 → level 3
        assertEquals(gold + c3.gems, clubs.findById(club.id)!!.gold)
    }

    @Test
    fun `the last chapter brings Toya and ends the season`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val c6 = Story.chapter(6)!!
        stories.save(StoryProgress(club.id, 6, emptyMap(), clock.now()))
        // the final: four wins with Jun (owned for the test), two clean sheets, the story match
        clubs.update(clubs.findById(club.id)!!.copy(gold = 1000)); heroUse.unlock(u1, "p008")
        repeat(2) { cards.report(u1, match(true, 2, 1, "p008", "p001")) }
        assertFalse(cards.report(u1, match(true, 1, 0, "p008")).story!!.chapterDone)
        val last = cards.report(u1, match(true, 1, 0, "p008", story = 6))
        assertTrue(last.story!!.chapterDone)
        val r = story.claim(u1, 6)
        assertEquals("p002", r.hero!!.hero.spec.id); assertTrue(r.hero!!.joined)
        assertEquals(c6.coins, r.coins)
        assertTrue(r.story.finished); assertEquals(7, r.story.current)
        assertTrue(r.story.chapters.all { it.state == ChapterState.CLAIMED })
        assertNull(cards.report(u1, match(true, 2, 0, "p001")).story)   // nothing more to count
        assertFailsWith<ConflictException> { story.claim(u1, 6) }
    }
}
