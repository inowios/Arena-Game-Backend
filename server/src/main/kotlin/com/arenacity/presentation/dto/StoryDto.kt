package com.arenacity.presentation.dto

import com.arenacity.domain.rules.Story
import com.arenacity.domain.usecase.story.ChapterView
import com.arenacity.domain.usecase.story.StoryClaimResult
import com.arenacity.domain.usecase.story.StoryUpdate
import com.arenacity.domain.usecase.story.StoryView
import kotlinx.serialization.Serializable

/** A chapter goal: [id] «cN.oK» is the client's text key (story.cN.oK); kind PLAY_WITH | WIN_WITH | WIN | GOALS | CLEAN_WIN | STORY_WIN. */
@Serializable data class StoryObjectiveDto(val id: String, val kind: String, val target: Int, val progress: Int, val done: Boolean, val heroes: List<String> = emptyList())
/** The chapter's story match opponent: [key] story.rival.cN, its average rating, its star hero (null = none) and his level. */
@Serializable data class StoryRivalDto(val key: String, val strength: Int, val star: String?, val starLevel: Int)
/** What claiming the chapter gives: the story hero (null for chapter 1), coins and gems (the club's gold). */
@Serializable data class StoryRewardDto(val hero: String?, val coins: Long, val gems: Int)
/** A chapter: [key] story.cN (the title story.cN.title, the intro story.cN.intro), [state] LOCKED | ACTIVE | DONE | CLAIMED. */
@Serializable data class StoryChapterDto(val number: Int, val key: String, val titleKey: String, val introKey: String, val state: String, val rival: StoryRivalDto,
                                         val objectives: List<StoryObjectiveDto>, val reward: StoryRewardDto)
/** GET /club/story: [chapter] = the one the club works on ([finished] once all six are claimed; it is then 7). */
@Serializable data class StoryResponse(val season: String, val chapter: Int, val finished: Boolean, val chapters: List<StoryChapterDto>)
/** POST /club/story/{chapter}/claim: the season now, the club, the story hero ([newHero] false = he was owned and got [heroXp]), the coins and gems given. */
@Serializable data class StoryClaimResponse(val story: StoryResponse, val club: ClubDto, val chapter: Int, val hero: HeroDto?, val newHero: Boolean, val heroXp: Int, val coins: Long, val gems: Int)
/** In the card match result: the current chapter after the match, the goals it completed, whether the whole chapter is done now. */
@Serializable data class StoryMatchDto(val chapter: StoryChapterDto, val completed: List<String>, val chapterDone: Boolean)

fun ChapterView.toDto(): StoryChapterDto = StoryChapterDto(
    chapter.number, chapter.key, chapter.key + ".title", chapter.key + ".intro", state.name,
    StoryRivalDto(chapter.rival.key, chapter.rival.strength, chapter.rival.star, chapter.rival.starLevel),
    chapter.objectives.map { o -> StoryObjectiveDto(o.id, o.kind.name, o.target, progressOf(o.id), progressOf(o.id) >= o.target, o.heroes) },
    StoryRewardDto(chapter.hero, chapter.coins, chapter.gems),
)
fun StoryView.toDto() = StoryResponse(Story.SEASON, current, finished, chapters.map { it.toDto() })
fun StoryClaimResult.toDto() = StoryClaimResponse(story.toDto(), club.toDto(), chapter, hero?.hero?.toDto(), hero?.joined ?: false, hero?.xp ?: 0, coins, gems)
fun StoryUpdate.toDto() = StoryMatchDto(chapter.toDto(), completed, chapterDone)
