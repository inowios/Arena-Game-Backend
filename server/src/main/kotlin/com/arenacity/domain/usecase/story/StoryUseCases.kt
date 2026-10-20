package com.arenacity.domain.usecase.story

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.model.Club
import com.arenacity.domain.model.StoryProgress
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.repository.StoryRepository
import com.arenacity.domain.rules.ChapterState
import com.arenacity.domain.rules.Story
import com.arenacity.domain.rules.StoryChapter
import com.arenacity.domain.rules.StoryMatch
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.hero.HeroStoryGrant
import com.arenacity.domain.usecase.hero.HeroUseCases
import java.util.UUID

/** A chapter as the club sees it: its [state] and the goals' [progress] (empty for chapters other than the current one; claimed ones show full). */
data class ChapterView(val chapter: StoryChapter, val state: ChapterState, val progress: Map<String, Int>) {
    fun progressOf(id: String): Int = progress[id] ?: 0
}
/** The whole season: [current] = the chapter the club works on (Story.LAST + 1 when the season is over). */
data class StoryView(val current: Int, val chapters: List<ChapterView>) {
    val finished get() = current > Story.LAST
}
/** What a match did to the story: the current chapter now, the goals it completed and whether it completed the chapter. */
data class StoryUpdate(val chapter: ChapterView, val completed: List<String>, val chapterDone: Boolean)
/** A chapter claimed: the season now, the club after the reward, the story hero (null for chapter 1), coins and gems given. */
data class StoryClaimResult(val story: StoryView, val club: Club, val hero: HeroStoryGrant?, val coins: Long, val gems: Int, val chapter: Int)

/**
 * The story «Іскра» (rules.Story): the season's chapters with the club's progress, the progress an Arena match adds
 * (called from usecase.arena.ArenaMatchUseCases.report, in its transaction), and claiming a finished chapter — its story
 * hero (or experience for him when the club bought him already), its coins and gems, and the next chapter opens.
 */
class StoryUseCases(private val service: ClubService, private val clubs: ClubRepository, private val stories: StoryRepository, private val heroes: HeroUseCases,
                    private val clock: Clock, private val tx: Tx = NoTx) {

    private suspend fun progressOf(clubId: UUID): StoryProgress = stories.get(clubId) ?: StoryProgress(clubId, 1, emptyMap(), clock.now())

    private fun view(p: StoryProgress): StoryView = StoryView(p.chapter, Story.CHAPTERS.map { c ->
        val state = Story.state(c.number, p.chapter, p.progress)
        ChapterView(c, state, when (state) {
            ChapterState.CLAIMED -> c.objectives.associate { it.id to it.target }
            ChapterState.LOCKED -> emptyMap()
            else -> c.objectives.associate { it.id to minOf(it.target, p.progress[it.id] ?: 0) }
        })
    })

    suspend fun story(userId: UUID): StoryView = view(progressOf(service.clubOf(userId).id))

    /**
     * Adds a finished Arena match to the club's current chapter (inside the report's transaction, the club locked). The story
     * match goal counts only when the report's «story» is that chapter. Nothing changes once the season is over or the
     * chapter's goals are all reached (it waits to be claimed); null then.
     */
    suspend fun record(club: Club, m: StoryMatch): StoryUpdate? {
        val p = progressOf(club.id)
        val chapter = Story.chapter(p.chapter) ?: return null
        if (Story.complete(chapter, p.progress)) return StoryUpdate(view(p).chapters[chapter.number - 1], emptyList(), false)
        val next = Story.progress(chapter, p.progress, m)
        val completed = chapter.objectives.filter { (p.progress[it.id] ?: 0) < it.target && (next[it.id] ?: 0) >= it.target }.map { it.id }
        val saved = if (next.any { (k, v) -> v != (p.progress[k] ?: 0) }) stories.save(p.copy(progress = next, updatedAt = clock.now())) else p
        return StoryUpdate(view(saved).chapters[chapter.number - 1], completed, Story.complete(chapter, next))
    }

    /**
     * Claims chapter [number]: it must be the club's current chapter with every goal reached (409 otherwise; 404 for no
     * such chapter). Gives its story hero — new in the club, or [Story.OWNED_HERO_XP] experience for him when the club
     * bought him for gems already — its coins and gems, and opens the next chapter.
     */
    suspend fun claim(userId: UUID, number: Int): StoryClaimResult = tx {
        val chapter = Story.chapter(number) ?: throw NotFoundException("No such chapter")
        val st = service.state(userId, lock = true)
        val p = progressOf(st.club.id)
        if (number < p.chapter) throw ConflictException("The chapter is already claimed")
        if (number > p.chapter) throw ConflictException("The chapter is not open yet")
        if (!Story.complete(chapter, p.progress)) throw ConflictException("Complete the chapter's goals first")
        val grant = chapter.hero?.let { heroes.grantStory(st.club, it, st.stadium, Story.OWNED_HERO_XP) }
        val club = clubs.update(st.club.copy(coins = st.club.coins + chapter.coins, gold = st.club.gold + chapter.gems, updatedAt = clock.now()))
        val saved = stories.save(StoryProgress(club.id, number + 1, emptyMap(), clock.now()))
        StoryClaimResult(view(saved), club, grant, chapter.coins, chapter.gems, number)
    }
}
