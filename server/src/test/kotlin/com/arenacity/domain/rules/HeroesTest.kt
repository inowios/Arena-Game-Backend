package com.arenacity.domain.rules

import com.arenacity.domain.model.HeroGroup
import com.arenacity.domain.model.People
import kotlin.test.*

class HeroesTest {
    private fun hero(id: String) = HeroCatalog.of(id)!!

    @Test
    fun `the catalog has the 51 heroes of people json, the eleven humans with an Arena v4 kit`() {
        assertEquals(51, HeroCatalog.ALL.size)
        assertEquals(51, HeroCatalog.ALL.map { it.id }.toSet().size)
        assertEquals((1..51).map { "p%03d".format(it) }, HeroCatalog.ALL.map { it.id })
        assertEquals(11, HeroCatalog.ALL.count { it.people == null })
        People.entries.forEach { p -> assertEquals(10, HeroCatalog.ALL.count { it.people == p }, "$p") }
        assertNull(HeroCatalog.of("p999"))
        // the kits (arena_skills.json): four skills s0..s3 each, Mirai's alternate, three passives, a colour; the peoples' heroes none yet
        assertEquals((1..11).map { "p%03d".format(it) }, HeroCatalog.WITH_KIT.map { it.id })
        assertTrue(HeroCatalog.WITH_KIT.all { h -> h.hasKit && h.skills == (0..3).map { "${h.id}.s$it" } && h.colour in setOf("speed", "technique", "tactics", "strength") })
        assertEquals(listOf("p004.a0"), HeroCatalog.WITH_KIT.flatMap { it.alternates })
        assertEquals(listOf("p001.p0", "p002.p0", "p007.p0"), HeroCatalog.WITH_KIT.flatMap { it.passives })
        assertTrue(HeroCatalog.ALL.filter { it.people != null }.all { !it.hasKit && it.skills.isEmpty() && it.colour == null })
        assertEquals("GK", hero("p011").position)   // Hikaru: an ordinary pick in the Arena
    }

    @Test
    fun `the start six are the approved humans and are never sold`() {
        val names = Heroes.START.map { hero(it).name }
        assertEquals(listOf("Kaito Homura", "Itsuki Neiro", "Hikaru Tobita", "Ganji Tsuchiya", "Hayate Kazama", "Mirai Shion"), names)
        Heroes.START.forEach { id ->
            val rule = Heroes.rule(hero(id))
            assertEquals(UnlockKind.START, rule.kind); assertEquals(0, rule.gold)
            assertFalse(Heroes.canBuy(rule, stadium = 10, gold = 100_000))
        }
    }

    @Test
    fun `story heroes open by chapter and can be bought for gold at any stadium`() {
        val expected = mapOf("Shizuku Isonami" to (2 to 300), "Raiga Kamiya" to (3 to 900), "Rei Kasumi" to (4 to 500), "Jun Omori" to (5 to 500), "Toya Shimotsuki" to (6 to 1500))
        for ((name, cp) in expected) {
            val h = HeroCatalog.ALL.single { it.name == name }
            val rule = Heroes.rule(h)
            assertEquals(UnlockKind.STORY, rule.kind, name); assertEquals(cp.first, rule.chapter, name); assertEquals(cp.second, rule.gold, name)
            assertEquals(1, rule.stadium)
            assertTrue(Heroes.canBuy(rule, stadium = 1, gold = cp.second)); assertFalse(Heroes.canBuy(rule, stadium = 1, gold = cp.second - 1))
        }
        // every human is either a start hero or a story hero
        assertTrue(HeroCatalog.ALL.filter { it.people == null }.all { it.id in Heroes.START || it.id in Heroes.STORY })
    }

    @Test
    fun `peoples open with the stadium and cost by rarity, but wait for their Arena kits`() {
        assertEquals(listOf(300, 500, 900, 1500), HeroGroup.entries.map { Heroes.price(it) })
        val stadium = mapOf(People.BEAST to 4, People.STONE to 5, People.ANDROID to 7, People.SHADOW to 9)   // the map's islands (rules.Zones)
        assertTrue(HeroCatalog.WITH_KIT.all { Heroes.unavailableReason(it) == null })
        for (h in HeroCatalog.ALL.filter { it.people != null }) {
            assertEquals(Heroes.REASON_SOON, Heroes.unavailableReason(h), h.id)
            val rule = Heroes.rule(h)
            assertEquals(UnlockKind.FOLK, rule.kind); assertNull(rule.chapter)
            assertEquals(stadium[h.people], rule.stadium, h.id); assertEquals(Heroes.price(h.group), rule.gold, h.id)
            assertFalse(Heroes.available(rule, rule.stadium - 1)); assertTrue(Heroes.available(rule, rule.stadium))
            assertFalse(Heroes.canBuy(rule, rule.stadium - 1, 100_000))
            assertFalse(Heroes.canBuy(rule, rule.stadium, rule.gold - 1))
            assertTrue(Heroes.canBuy(rule, rule.stadium, rule.gold))
        }
        assertEquals(1500, Heroes.rule(hero("p042")).gold)   // Noctis Vale, a shadow legend
        assertEquals(9, Heroes.rule(hero("p042")).stadium)
    }

    @Test
    fun `the level curve rises to 10 and stops there`() {
        assertEquals(Heroes.MAX_LEVEL - 1, Heroes.XP_TO_NEXT.size)
        assertTrue(Heroes.XP_TO_NEXT.zipWithNext().all { (a, b) -> b > a }, "increasing thresholds")
        assertEquals(100, Heroes.xpToNext(1)); assertNull(Heroes.xpToNext(10))
        assertEquals(1 to 99, Heroes.grow(1, 0, 99))
        assertEquals(2 to 0, Heroes.grow(1, 0, 100))
        assertEquals(3 to 10, Heroes.grow(1, 90, 170))   // 100 to level 2, 150 to level 3, 10 over
        assertEquals(10 to 0, Heroes.grow(1, 0, Heroes.XP_TO_NEXT.sum()))
        assertEquals(10 to 0, Heroes.grow(9, 999, 1_000_000))
        assertEquals(10 to 0, Heroes.grow(10, 0, 50))
        assertEquals(1 to 5, Heroes.grow(1, 5, -40))   // never backwards
    }

    @Test
    fun `the league triple is the saved lineup filled with the best owned heroes with a kit`() {
        assertEquals(hero("p001").skills, hero("p001").skills.distinct())
        val club = java.util.UUID.randomUUID(); val now = java.time.Instant.EPOCH
        fun own(id: String, level: Int = 1, xp: Int = 0) = com.arenacity.domain.model.ClubHero(club, id, level, xp, com.arenacity.domain.model.HeroSource.START, now)
        val owned = listOf(own("p001"), own("p003", 2), own("p011", 2, 30), own("p007", 5), own("p009"), own("p004"), own("p012", 9))   // p012: no kit
        val lineup = com.arenacity.domain.model.HeroLineup(club, listOf("p004", "p009"), now)
        assertEquals(listOf("p004", "p009", "p007"), HeroSquad.triple(owned, lineup).map { it.id })          // the saved two, then the level-5 Ganji
        assertEquals(listOf(1, 1, 5), HeroSquad.triple(owned, lineup).map { it.level })
        assertEquals(listOf("p007", "p011", "p003"), HeroSquad.triple(owned, null).map { it.id })            // no lineup: level, then experience, then the catalog
        assertEquals(listOf("p001"), HeroSquad.triple(listOf(own("p001"), own("p012")), null).map { it.id })  // a kit-less hero never plays
        assertEquals(3, HeroSquad.seeded(5).map { it.id }.toSet().size); assertEquals(HeroSquad.seeded(5).map { it.id }, HeroSquad.seeded(5).map { it.id })
        assertTrue(HeroSquad.seeded(5).all { it.id in HeroSquad.HUMANS && it.level == 1 })
    }

    @Test
    fun `match experience follows the result`() {
        assertEquals(50, Heroes.matchXp(won = true, draw = false))
        assertEquals(35, Heroes.matchXp(won = false, draw = true))
        assertEquals(20, Heroes.matchXp(won = false, draw = false))
        assertEquals(0, Heroes.matchXp(won = false, draw = false, conceded = true))
        assertTrue(Heroes.MAX_MATCH_XP_PER_DAY % Heroes.matchXp(true, false) == 0)
    }

    @Test
    fun `the Arena triple takes owned heroes with a kit once, any position, at most three`() {
        val owned = (Heroes.START + "p006" + "p012").associateWith { hero(it) }
        assertEquals(3, Heroes.MAX_HEROES_PER_MATCH)
        assertNull(Heroes.lineupError(listOf("p001", "p003", "p011"), owned))   // Hikaru like anyone else
        assertNull(Heroes.lineupError(emptyList(), owned))                       // the match fills the triple itself
        assertNull(Heroes.lineupError(listOf("p006"), owned))
        assertEquals("Too many heroes" to null, Heroes.lineupError(listOf("p001", "p003", "p007", "p009"), owned))
        assertEquals("Hero {0} is not in your club" to "p010", Heroes.lineupError(listOf("p001", "p010"), owned))
        assertEquals("Hero {0} is not in your club" to "p999", Heroes.lineupError(listOf("p999"), owned))
        assertEquals("Hero {0} has no Arena kit yet" to "p012", Heroes.lineupError(listOf("p001", "p012"), owned))
        assertEquals("A hero can only be picked once" to null, Heroes.lineupError(listOf("p001", "p003", "p001"), owned))
    }

    @Test
    fun `training pays more and gives more with the stadium's level`() {
        assertEquals(10, Heroes.TRAINING_XP.size); assertEquals(10, Heroes.TRAINING_COINS.size)
        assertEquals(MapSpots.MAX_STADIUM, Heroes.TRAINING_XP.size)
        assertTrue(Heroes.TRAINING_XP.zipWithNext().all { (a, b) -> b > a }); assertTrue(Heroes.TRAINING_COINS.zipWithNext().all { (a, b) -> b > a })
        assertEquals(80, Heroes.trainingXp(1)); assertEquals(200L, Heroes.trainingCoins(1))
        assertEquals(300, Heroes.trainingXp(8)); assertEquals(6_000L, Heroes.trainingCoins(8))
        assertEquals(380, Heroes.trainingXp(12)); assertEquals(80, Heroes.trainingXp(0))   // clamped to the stadium's levels
        assertEquals(listOf(1, 1, 2, 2, 3, 3, 4, 4, 5, 5), (1..10).map { Heroes.trainingSlots(it) })
        assertEquals(7200L, Heroes.TRAINING_SECONDS)
        assertEquals(0, Heroes.totalXp(1, 0)); assertEquals(250 + 20, Heroes.totalXp(3, 20)); assertEquals(4170, Heroes.totalXp(10, 0))
    }
}
