package com.arenacity.domain.rules

import com.arenacity.domain.model.HeroGroup
import com.arenacity.domain.model.People
import kotlin.test.*

class HeroesTest {
    private fun hero(id: String) = HeroCatalog.of(id)!!

    @Test
    fun `the catalog has the 51 heroes of people json with three advanced skills each`() {
        assertEquals(51, HeroCatalog.ALL.size)
        assertEquals(51, HeroCatalog.ALL.map { it.id }.toSet().size)
        assertEquals((1..51).map { "p%03d".format(it) }, HeroCatalog.ALL.map { it.id })
        assertTrue(HeroCatalog.ALL.all { h -> h.skills == listOf("${h.id}.s4", "${h.id}.s5", "${h.id}.s6") })
        assertEquals(11, HeroCatalog.ALL.count { it.people == null })
        People.entries.forEach { p -> assertEquals(10, HeroCatalog.ALL.count { it.people == p }, "$p") }
        assertNull(HeroCatalog.of("p999"))
    }

    @Test
    fun `the start six are the approved humans and are never sold`() {
        val names = Heroes.START.map { hero(it).name }
        assertEquals(listOf("Kaito Homura", "Itsuki Neiro", "Hikaru Tobita", "Ganji Tsuchiya", "Hayate Kazama", "Shizuku Isonami"), names)
        assertEquals("GK", hero("p011").position)
        assertEquals(1, Heroes.START.count { hero(it).position == "GK" })
        Heroes.START.forEach { id ->
            val rule = Heroes.rule(hero(id))
            assertEquals(UnlockKind.START, rule.kind); assertEquals(0, rule.gold)
            assertFalse(Heroes.canBuy(rule, stadium = 10, gold = 100_000))
        }
    }

    @Test
    fun `story heroes open by chapter and can be bought for gold at any stadium`() {
        val expected = mapOf("Mirai Shion" to (2 to 900), "Raiga Kamiya" to (3 to 900), "Rei Kasumi" to (4 to 500), "Jun Omori" to (5 to 500), "Toya Shimotsuki" to (6 to 1500))
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
    fun `peoples open with the stadium and cost by rarity`() {
        assertEquals(listOf(300, 500, 900, 1500), HeroGroup.entries.map { Heroes.price(it) })
        val stadium = mapOf(People.BEAST to 4, People.STONE to 5, People.ANDROID to 7, People.SHADOW to 10)
        for (h in HeroCatalog.ALL.filter { it.people != null }) {
            val rule = Heroes.rule(h)
            assertEquals(UnlockKind.FOLK, rule.kind); assertNull(rule.chapter)
            assertEquals(stadium[h.people], rule.stadium, h.id); assertEquals(Heroes.price(h.group), rule.gold, h.id)
            assertFalse(Heroes.available(rule, rule.stadium - 1)); assertTrue(Heroes.available(rule, rule.stadium))
            assertFalse(Heroes.canBuy(rule, rule.stadium - 1, 100_000))
            assertFalse(Heroes.canBuy(rule, rule.stadium, rule.gold - 1))
            assertTrue(Heroes.canBuy(rule, rule.stadium, rule.gold))
        }
        assertEquals(1500, Heroes.rule(hero("p042")).gold)   // Noctis Vale, a shadow legend
        assertEquals(10, Heroes.rule(hero("p042")).stadium)
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
    fun `advanced skills open at levels 4, 7 and 10 and the boost reaches 10 percent`() {
        val kai = hero("p001")
        assertEquals(emptyList(), Heroes.unlockedSkills(kai, 3))
        assertEquals(listOf("p001.s4"), Heroes.unlockedSkills(kai, 4))
        assertEquals(listOf("p001.s4", "p001.s5"), Heroes.unlockedSkills(kai, 9))
        assertEquals(kai.skills, Heroes.unlockedSkills(kai, 10))
        assertEquals(4, Heroes.nextSkillLevel(1)); assertEquals(7, Heroes.nextSkillLevel(4)); assertNull(Heroes.nextSkillLevel(10))
        assertEquals(0, Heroes.boostPct(1)); assertEquals(10, Heroes.boostPct(10))
        assertTrue((1..10).map { Heroes.boostPct(it) }.zipWithNext().all { (a, b) -> b >= a })
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
    fun `a lineup takes owned heroes once, a goalkeeper in goal, at most five, four and ten`() {
        val owned = (Heroes.START + "p006").associateWith { hero(it) }
        val five = listOf("p001", "p003", "p007", "p009", "p010")
        assertNull(Heroes.lineupError(five, "p011", listOf("p006"), owned))
        assertNull(Heroes.lineupError(emptyList(), null, emptyList(), owned))   // the academy's pupils fill everything
        assertNull(Heroes.lineupError(listOf("p001"), null, emptyList(), owned))
        assertEquals("Too many heroes" to null, Heroes.lineupError(five + "p006", "p011", emptyList(), owned))
        assertEquals("Too many heroes" to null, Heroes.lineupError(emptyList(), null, listOf("p001", "p003", "p007", "p009", "p010"), owned))
        assertEquals("Hero {0} is not in your club" to "p004", Heroes.lineupError(listOf("p001", "p004"), "p011", emptyList(), owned))
        assertEquals("Hero {0} is not in your club" to "p999", Heroes.lineupError(emptyList(), null, listOf("p999"), owned))
        assertEquals("A hero can only be picked once" to null, Heroes.lineupError(listOf("p001"), "p011", listOf("p001"), owned))
        assertEquals("The keeper must be a goalkeeper" to null, Heroes.lineupError(listOf("p003"), "p001", emptyList(), owned))
        assertEquals("A goalkeeper only plays in goal" to null, Heroes.lineupError(listOf("p011"), null, emptyList(), owned))
        assertEquals("A goalkeeper only plays in goal" to null, Heroes.lineupError(emptyList(), null, listOf("p011"), owned))
        assertEquals(Heroes.MAX_HEROES_PER_MATCH, Heroes.LINEUP_FIELD + 1 + Heroes.LINEUP_BENCH)
    }

    @Test
    fun `training pays more and gives more with the training centre's level`() {
        assertEquals(8, Heroes.TRAINING_XP.size); assertEquals(8, Heroes.TRAINING_COINS.size)
        assertEquals(BuildingCatalog.spec(BuildingType.TRAINING_CENTER).levels.size, Heroes.TRAINING_XP.size)
        assertTrue(Heroes.TRAINING_XP.zipWithNext().all { (a, b) -> b > a }); assertTrue(Heroes.TRAINING_COINS.zipWithNext().all { (a, b) -> b > a })
        assertEquals(80, Heroes.trainingXp(1)); assertEquals(200L, Heroes.trainingCoins(1))
        assertEquals(300, Heroes.trainingXp(8)); assertEquals(6_000L, Heroes.trainingCoins(8))
        assertEquals(300, Heroes.trainingXp(12)); assertEquals(80, Heroes.trainingXp(0))   // clamped to the centre's levels
        assertEquals(listOf(1, 1, 2, 2, 3, 3, 4, 4), (1..8).map { Heroes.trainingSlots(it) })
        assertEquals(7200L, Heroes.TRAINING_SECONDS)
        assertEquals(0, Heroes.totalXp(1, 0)); assertEquals(250 + 20, Heroes.totalXp(3, 20)); assertEquals(4170, Heroes.totalXp(10, 0))
    }
}
