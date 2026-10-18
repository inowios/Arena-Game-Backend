package com.arenacity.domain.rules

/**
 * What a chapter goal counts, all of it readable from a card match report (POST /club/cards/result):
 * PLAY_WITH — matches played with all of [StoryObjective.heroes] on the pitch; WIN_WITH — wins with them;
 * WIN — wins; GOALS — goals scored (any result); CLEAN_WIN — wins without conceding;
 * STORY_WIN — the chapter's story match won (the report's «story» = this chapter, and only while it is the club's current one).
 */
enum class ObjectiveKind { PLAY_WITH, WIN_WITH, WIN, GOALS, CLEAN_WIN, STORY_WIN }

/** One chapter goal: [id] = «cN.oK» (the client's text key story.cN.oK), [target] to reach, [heroes] for PLAY_WITH / WIN_WITH. */
data class StoryObjective(val id: String, val kind: ObjectiveKind, val target: Int, val heroes: List<String> = emptyList())

/**
 * The chapter's story match opponent, a named AI team the device builds (SquadBuilder.Opponent): [key] = story.rival.cN,
 * [strength] = its average rating (the start six at level 1 are 69), [star] = a story hero playing for it (his portrait and
 * skills on the other side) at [starLevel], null when the team has none.
 */
data class StoryRival(val key: String, val strength: Int, val star: String? = null, val starLevel: Int = 1)

/** A chapter of «Іскра»: its goals, its rival, and what claiming it gives — [hero] (the story hero it opens), coins and gems (gold). */
data class StoryChapter(val number: Int, val rival: StoryRival, val objectives: List<StoryObjective>, val hero: String?, val coins: Long, val gems: Int) {
    val key get() = "story.c$number"
}

/** What a finished card match tells the story: [heroes] = the club's own heroes who played (unknown ids already dropped), [story] = the chapter of a story match. */
data class StoryMatch(val won: Boolean, val draw: Boolean, val goalsFor: Int, val goalsAgainst: Int, val heroes: Set<String>, val story: Int? = null, val conceded: Boolean = false)

/** A chapter as one club sees it. */
enum class ChapterState { LOCKED, ACTIVE, DONE, CLAIMED }

/**
 * The story campaign «Іскра», season 1 (docs/claude/HEROES-INSTEAD-OF-CARDS.md §12 in the mobile repo): six chapters, each
 * 2–3 goals checked from card match reports, always ending with the chapter's story match against a named rival. A club
 * works on one chapter at a time ([club_story].chapter); claiming it gives the chapter's story hero (rules.Heroes.STORY)
 * and a small reward and opens the next. Goals grow from 2–3 matches (chapter 1, at least 2) to ~6 (chapter 6, at least 4); «Стрілка» averages
 * two goals a match, so the goal counts stay modest, and one 3:0 story match never finishes a chapter alone.
 */
object Story {
    const val SEASON = "spark"
    /** A story hero the club already has (bought for gems before the chapter) gets this much experience instead of himself. */
    const val OWNED_HERO_XP = 250

    private fun o(chapter: Int, k: Int, kind: ObjectiveKind, target: Int, vararg heroes: String) = StoryObjective("c$chapter.o$k", kind, target, heroes.toList())

    val CHAPTERS: List<StoryChapter> = listOf(
        // 1 «Хлопчик з іскрою» — the beach: Kai and Itsuki's first match (a tutorial: play with the pair, score, beat the harbour kids)
        StoryChapter(1, StoryRival("story.rival.c1", 62), listOf(
            o(1, 1, ObjectiveKind.PLAY_WITH, 2, "p001", "p003"),
            o(1, 2, ObjectiveKind.GOALS, 3),
            o(1, 3, ObjectiveKind.STORY_WIN, 1),
        ), hero = null, coins = 500, gems = 5),
        // 2 «Тиха вода» — Shizuku, the Bay Sails' left back, joins once the pair beat her team (she was Mirai's chapter until
        // 18 October 2026, when Mirai moved to the start six)
        StoryChapter(2, StoryRival("story.rival.c2", 65), listOf(
            o(2, 1, ObjectiveKind.WIN_WITH, 2, "p001", "p003"),
            o(2, 2, ObjectiveKind.GOALS, 5),
            o(2, 3, ObjectiveKind.STORY_WIN, 1),
        ), hero = "p010", coins = 800, gems = 10),
        // 3 «Король двору» — Raiga's street team: Mirai draws the lines for Kai
        StoryChapter(3, StoryRival("story.rival.c3", 67, star = "p005", starLevel = 3), listOf(
            o(3, 1, ObjectiveKind.WIN_WITH, 3, "p001", "p004"),
            o(3, 2, ObjectiveKind.GOALS, 6),
            o(3, 3, ObjectiveKind.STORY_WIN, 1),
        ), hero = "p005", coins = 1200, gems = 10),
        // 4 «Туман над гаванню» — Rei appears out of the fog of the harbour team
        StoryChapter(4, StoryRival("story.rival.c4", 69, star = "p006", starLevel = 4), listOf(
            o(4, 1, ObjectiveKind.WIN, 4),
            o(4, 2, ObjectiveKind.CLEAN_WIN, 1),
            o(4, 3, ObjectiveKind.STORY_WIN, 1),
        ), hero = "p006", coins = 1600, gems = 15),
        // 5 «Академія» — the Citadel Academy's youth team with Jun, before he is thrown out and joins
        StoryChapter(5, StoryRival("story.rival.c5", 71, star = "p008", starLevel = 5), listOf(
            o(5, 1, ObjectiveKind.WIN_WITH, 4, "p004", "p005"),
            o(5, 2, ObjectiveKind.GOALS, 8),
            o(5, 3, ObjectiveKind.STORY_WIN, 1),
        ), hero = "p008", coins = 2200, gems = 15),
        // 6 «Фінал. Іскра» — the final against the Academy and Toya; the rival comes over to us
        StoryChapter(6, StoryRival("story.rival.c6", 74, star = "p002", starLevel = 7), listOf(
            o(6, 1, ObjectiveKind.WIN_WITH, 4, "p008"),
            o(6, 2, ObjectiveKind.CLEAN_WIN, 2),
            o(6, 3, ObjectiveKind.STORY_WIN, 1),
        ), hero = "p002", coins = 3000, gems = 25),
    )

    val LAST = CHAPTERS.size

    fun chapter(number: Int): StoryChapter? = CHAPTERS.getOrNull(number - 1)

    /** How much one match adds to [o] of chapter [chapter]; a conceded match counts for nothing. */
    fun gain(o: StoryObjective, chapter: Int, m: StoryMatch): Int {
        if (m.conceded) return 0
        val withAll = o.heroes.isNotEmpty() && m.heroes.containsAll(o.heroes)
        return when (o.kind) {
            ObjectiveKind.PLAY_WITH -> if (withAll) 1 else 0
            ObjectiveKind.WIN_WITH -> if (withAll && m.won) 1 else 0
            ObjectiveKind.WIN -> if (m.won) 1 else 0
            ObjectiveKind.GOALS -> m.goalsFor.coerceAtLeast(0)
            ObjectiveKind.CLEAN_WIN -> if (m.won && m.goalsAgainst == 0) 1 else 0
            ObjectiveKind.STORY_WIN -> if (m.won && m.story == chapter) 1 else 0
        }
    }

    /** The chapter's progress after [m]: each goal grows by [gain], never past its target. */
    fun progress(chapter: StoryChapter, before: Map<String, Int>, m: StoryMatch): Map<String, Int> =
        chapter.objectives.associate { o -> o.id to minOf(o.target, (before[o.id] ?: 0) + gain(o, chapter.number, m)) }

    /** Every goal of the chapter reached. */
    fun complete(chapter: StoryChapter, progress: Map<String, Int>): Boolean = chapter.objectives.all { (progress[it.id] ?: 0) >= it.target }

    /** [number]'s state while the club works on [current] (LAST + 1 = the season is over). */
    fun state(number: Int, current: Int, progress: Map<String, Int>): ChapterState {
        val c = chapter(number)
        return when {
            number < current -> ChapterState.CLAIMED
            number > current || c == null -> ChapterState.LOCKED
            complete(c, progress) -> ChapterState.DONE
            else -> ChapterState.ACTIVE
        }
    }

    /** The progress as stored: «c1.o1=1,c1.o2=3». */
    fun encode(progress: Map<String, Int>): String = progress.entries.filter { it.value > 0 }.joinToString(",") { "${it.key}=${it.value}" }
    fun decode(text: String?): Map<String, Int> = text.orEmpty().split(',').mapNotNull { part ->
        val i = part.indexOf('='); if (i <= 0) null else part.substring(i + 1).toIntOrNull()?.let { part.substring(0, i) to it }
    }.toMap()
}
