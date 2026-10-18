package com.arenacity.domain.rules

import kotlin.test.*

class StoryTest {
    private fun m(won: Boolean = true, draw: Boolean = false, gf: Int = 2, ga: Int = 0, heroes: Set<String> = setOf("p001", "p003"), story: Int? = null, conceded: Boolean = false) =
        StoryMatch(won, draw, gf, ga, heroes, story, conceded)

    @Test
    fun `six chapters, each with 2-3 goals ending in its story match, giving the story heroes of rules Heroes`() {
        assertEquals(6, Story.CHAPTERS.size)
        Story.CHAPTERS.forEachIndexed { i, c ->
            assertEquals(i + 1, c.number)
            assertTrue(c.objectives.size in 2..3, "chapter ${c.number}")
            assertEquals(ObjectiveKind.STORY_WIN, c.objectives.last().kind)
            assertEquals(c.objectives.size, c.objectives.map { it.id }.toSet().size)
            assertTrue(c.objectives.all { it.id.startsWith("c${c.number}.o") && it.target >= 1 })
            assertTrue(c.objectives.filter { it.kind == ObjectiveKind.PLAY_WITH || it.kind == ObjectiveKind.WIN_WITH }.all { o -> o.heroes.isNotEmpty() && o.heroes.all { HeroCatalog.of(it) != null } })
            assertTrue(c.coins > 0 && c.gems > 0)
            assertEquals("story.rival.c${c.number}", c.rival.key)
        }
        // the hero of each chapter is the one rules.Heroes.STORY sells for that chapter
        assertEquals(Heroes.STORY, Story.CHAPTERS.mapNotNull { c -> c.hero?.let { it to c.number } }.toMap())
        assertNull(Story.chapter(1)!!.hero)
        // the rivals grow stronger, Toya plays for the last one
        assertEquals(Story.CHAPTERS.map { it.rival.strength }.sorted(), Story.CHAPTERS.map { it.rival.strength })
        assertEquals("p002", Story.chapter(6)!!.rival.star)
        assertEquals("p008", Story.chapter(5)!!.rival.star)
        // the heroes a goal needs are open by then: the start six, or the hero of an earlier chapter
        Story.CHAPTERS.forEach { c ->
            val open = Heroes.START + Story.CHAPTERS.filter { it.number < c.number }.mapNotNull { it.hero }
            c.objectives.forEach { o -> assertTrue(open.containsAll(o.heroes), "${o.id} needs ${o.heroes}") }
        }
    }

    @Test
    fun `each goal counts what a match report says`() {
        val c = Story.chapter(1)!!
        val p1 = Story.progress(c, emptyMap(), m(gf = 2, heroes = setOf("p001", "p003", "p011")))
        assertEquals(mapOf("c1.o1" to 1, "c1.o2" to 2, "c1.o3" to 0), p1)
        // Itsuki on the bench: the pair did not play; a loss still scores
        val p2 = Story.progress(c, p1, m(won = false, gf = 4, ga = 5, heroes = setOf("p001")))
        assertEquals(mapOf("c1.o1" to 1, "c1.o2" to 3, "c1.o3" to 0), p2)   // capped at the target 3
        assertFalse(Story.complete(c, p2))
        // the story match counts only for its own chapter and only when won
        assertEquals(0, Story.progress(c, p2, m(story = 2))["c1.o3"])
        assertEquals(0, Story.progress(c, p2, m(won = false, draw = true, story = 1))["c1.o3"])
        val p3 = Story.progress(c, p2, m(story = 1))
        assertEquals(1, p3["c1.o3"]); assertTrue(Story.complete(c, p3))
        // a conceded match counts for nothing
        assertEquals(emptyMap<String, Int>().let { c.objectives.associate { it.id to 0 } }, Story.progress(c, emptyMap(), m(conceded = true)))
    }

    @Test
    fun `wins with heroes and clean sheets`() {
        val c2 = Story.chapter(2)!!
        assertEquals(1, Story.gain(c2.objectives[0], 2, m()))
        assertEquals(0, Story.gain(c2.objectives[0], 2, m(won = false, draw = true)))
        assertEquals(0, Story.gain(c2.objectives[0], 2, m(heroes = setOf("p003"))))
        val c4 = Story.chapter(4)!!
        val clean = c4.objectives.single { it.kind == ObjectiveKind.CLEAN_WIN }
        assertEquals(1, Story.gain(clean, 4, m(gf = 1, ga = 0)))
        assertEquals(0, Story.gain(clean, 4, m(gf = 3, ga = 1)))
        assertEquals(0, Story.gain(clean, 4, m(won = false, draw = true, gf = 0, ga = 0)))
    }

    @Test
    fun `states and the stored form`() {
        val done = Story.chapter(2)!!.objectives.associate { it.id to it.target }
        assertEquals(ChapterState.CLAIMED, Story.state(1, 2, emptyMap()))
        assertEquals(ChapterState.ACTIVE, Story.state(2, 2, emptyMap()))
        assertEquals(ChapterState.DONE, Story.state(2, 2, done))
        assertEquals(ChapterState.LOCKED, Story.state(3, 2, done))
        assertEquals(ChapterState.CLAIMED, Story.state(6, 7, emptyMap()))
        val p = mapOf("c1.o1" to 1, "c1.o2" to 3, "c1.o3" to 0)
        assertEquals("c1.o1=1,c1.o2=3", Story.encode(p))
        assertEquals(mapOf("c1.o1" to 1, "c1.o2" to 3), Story.decode("c1.o1=1,c1.o2=3"))
        assertEquals(emptyMap(), Story.decode("")); assertEquals(emptyMap(), Story.decode(null)); assertEquals(mapOf("a" to 1), Story.decode("a=1,bad,=2,b=x"))
    }
}
