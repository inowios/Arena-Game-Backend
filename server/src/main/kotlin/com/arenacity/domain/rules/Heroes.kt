package com.arenacity.domain.rules

import com.arenacity.domain.model.HeroGroup
import com.arenacity.domain.model.HeroSpec
import com.arenacity.domain.model.People

/** How a hero opens: given at the start, a chapter of the story «Іскра», or its people's island (each also for gold). */
enum class UnlockKind { START, STORY, FOLK }

/**
 * A hero's unlock rule: [chapter] = the story chapter (STORY), [stadium] = the stadium level from which it can be opened,
 * [gold] = its price in gold («кристали»; 0 for the start six, which are never sold).
 */
data class HeroUnlock(val kind: UnlockKind, val chapter: Int? = null, val stadium: Int = 1, val gold: Int = 0)

/**
 * Heroes instead of cards (approved by the owner on 16 October 2026; docs/claude/HEROES-INSTEAD-OF-CARDS.md in the mobile
 * repo): each hero is owned once, for good, and grows from level 1 to [MAX_LEVEL] by playing card matches «Стрілка».
 * Every price and threshold of the feature is here.
 */
object Heroes {
    /** The six every new club gets (5 on the pitch + the keeper): Kai, Itsuki, Hikaru (GK), Ganji, Hayate, Shizuku. */
    val START = listOf("p001", "p003", "p011", "p007", "p009", "p010")

    /** The humans the story «Іскра» brings, by chapter (the chapter missions come later; for now they are bought for gold). */
    val STORY = mapOf("p004" to 2, "p005" to 3, "p006" to 4, "p008" to 5, "p002" to 6)

    /** The stadium level at which a people's heroes can be opened: the one its island's bridge needs (rules.Zones: 4 / 5 / 7 / 10). */
    val FOLK_STADIUM: Map<People, Int> get() = Zones.specs.mapValues { it.value.requiredStadium }

    /** The price in gold by rarity (story heroes too: Mirai, Raiga 900, Rei, Jun 500, Toya 1 500). */
    fun price(group: HeroGroup): Int = when (group) {
        HeroGroup.COMMON -> 300
        HeroGroup.GOOD -> 500
        HeroGroup.STAR -> 900
        HeroGroup.LEGEND -> 1500
    }

    fun rule(hero: HeroSpec): HeroUnlock = when {
        hero.id in START -> HeroUnlock(UnlockKind.START)
        hero.id in STORY -> HeroUnlock(UnlockKind.STORY, chapter = STORY.getValue(hero.id), gold = price(hero.group))
        else -> HeroUnlock(UnlockKind.FOLK, stadium = hero.people?.let { FOLK_STADIUM[it] } ?: 1, gold = price(hero.group))
    }

    /** The club's stadium has reached the rule's level (the start six and story heroes need none). */
    fun available(rule: HeroUnlock, stadium: Int): Boolean = stadium >= rule.stadium

    /** The hero can be bought now: it is for sale at all, the stadium is high enough and there is gold enough. */
    fun canBuy(rule: HeroUnlock, stadium: Int, gold: Int): Boolean = rule.kind != UnlockKind.START && available(rule, stadium) && gold >= rule.gold

    // --- growth

    const val MAX_LEVEL = 10

    /** Experience from one level to the next, levels 1→2 … 9→10 (4 170 in all: about 80 won matches). */
    val XP_TO_NEXT = listOf(100, 150, 220, 300, 400, 520, 660, 820, 1000)

    /** The levels that open the three advanced skills (the old card OVR 65 / 75 / 85). */
    val SKILL_LEVELS = listOf(4, 7, 10)

    /** Experience still needed at [level] for the next one; null at the top. */
    fun xpToNext(level: Int): Int? = if (level >= MAX_LEVEL) null else XP_TO_NEXT[level.coerceAtLeast(1) - 1]

    /** The level and the experience into it after [gained] more; at the top level the rest is dropped. */
    fun grow(level: Int, xp: Int, gained: Int): Pair<Int, Int> {
        var l = level.coerceIn(1, MAX_LEVEL); var x = xp + gained.coerceAtLeast(0)
        while (l < MAX_LEVEL && x >= XP_TO_NEXT[l - 1]) { x -= XP_TO_NEXT[l - 1]; l++ }
        return l to if (l >= MAX_LEVEL) 0 else x
    }

    /** All the experience a hero of [level] with [xp] into it has earned since level 1. */
    fun totalXp(level: Int, xp: Int): Int = XP_TO_NEXT.take(level.coerceIn(1, MAX_LEVEL) - 1).sum() + xp

    /** The advanced skills (ids) a hero of [level] has opened, in order. */
    fun unlockedSkills(hero: HeroSpec, level: Int): List<String> = hero.skills.filterIndexed { i, _ -> i < SKILL_LEVELS.size && level >= SKILL_LEVELS[i] }

    /** The level that opens the next advanced skill; null when all are open. */
    fun nextSkillLevel(level: Int): Int? = SKILL_LEVELS.firstOrNull { it > level }

    /** The strength bonus of [level] in percent: 0 at level 1 up to +10 % at level 10 (the concept's cap, so a hero never breaks a match). */
    fun boostPct(level: Int): Int = Math.round((level.coerceIn(1, MAX_LEVEL) - 1) * 10.0 / (MAX_LEVEL - 1)).toInt()

    // --- match experience (POST /club/cards/result)

    /** Heroes that may earn from one match: 5 on the pitch, the keeper and 4 substitutes. */
    const val MAX_HEROES_PER_MATCH = 10
    const val XP_PLAYED = 20
    const val XP_WIN = 30
    const val XP_DRAW = 15
    /** At most this much match experience per hero a UTC day (12 wins): the result is reported by the device, so farming stops here. */
    const val MAX_MATCH_XP_PER_DAY = 600

    /** Experience each hero who played gets: played + the result's bonus; a conceded match gives nothing. */
    fun matchXp(won: Boolean, draw: Boolean, conceded: Boolean = false): Int = when {
        conceded -> 0
        won -> XP_PLAYED + XP_WIN
        draw -> XP_PLAYED + XP_DRAW
        else -> XP_PLAYED
    }

    // --- the lineup («Склад»: GET / PUT /club/heroes/lineup)

    /** Field heroes of the «Стрілка» squad (the keeper apart) and its bench; [MAX_HEROES_PER_MATCH] = 5 + 1 + 4. */
    const val LINEUP_FIELD = 5
    const val LINEUP_BENCH = 4

    /**
     * What is wrong with a lineup (an error template and its argument), null when it is fine: at most [LINEUP_FIELD] field
     * heroes and [LINEUP_BENCH] on the bench (10 with the keeper), every hero owned ([owned]: id → spec) and picked once,
     * the keeper a goalkeeper and no goalkeeper in the field or on the bench (the match's bench holds field players only).
     * Fewer heroes is fine: the academy's pupils fill the empty places.
     */
    fun lineupError(field: List<String>, keeper: String?, bench: List<String>, owned: Map<String, HeroSpec>): Pair<String, String?>? {
        val all = field + bench + listOfNotNull(keeper)
        if (field.size > LINEUP_FIELD || bench.size > LINEUP_BENCH || all.size > MAX_HEROES_PER_MATCH) return "Too many heroes" to null
        all.firstOrNull { it !in owned }?.let { return "Hero {0} is not in your club" to it }
        if (all.toSet().size != all.size) return "A hero can only be picked once" to null
        if (keeper != null && owned.getValue(keeper).position != "GK") return "The keeper must be a goalkeeper" to null
        if ((field + bench).any { owned.getValue(it).position == "GK" }) return "A goalkeeper only plays in goal" to null
        return null
    }

    // --- training in the training centre (POST /club/heroes/{id}/train)

    /** One session is two hours: the timer, not a daily cap, is the limit (the match cap [MAX_MATCH_XP_PER_DAY] does not apply). */
    const val TRAINING_SECONDS = 2 * 3600L
    /** Experience of one session by the training centre's level 1..8. */
    val TRAINING_XP = listOf(80, 100, 120, 150, 180, 220, 260, 300)
    /** Its price in coins by the same level. */
    val TRAINING_COINS = listOf(200, 350, 600, 1_000, 1_600, 2_500, 4_000, 6_000)

    private fun tcIndex(trainingCenterLevel: Int) = trainingCenterLevel.coerceIn(1, TRAINING_XP.size) - 1
    fun trainingXp(trainingCenterLevel: Int): Int = TRAINING_XP[tcIndex(trainingCenterLevel)]
    fun trainingCoins(trainingCenterLevel: Int): Long = TRAINING_COINS[tcIndex(trainingCenterLevel)].toLong()
    /** Heroes training at once: the centre's slots (1, 1, 2, 2, 3, 3, 4, 4), as many as its athletes get (rules.TrainingRules.slots). */
    fun trainingSlots(trainingCenterLevel: Int): Int = TrainingRules.slots(trainingCenterLevel)
}
