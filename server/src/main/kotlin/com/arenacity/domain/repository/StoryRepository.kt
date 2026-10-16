package com.arenacity.domain.repository

import com.arenacity.domain.model.StoryProgress
import java.util.UUID

/** The clubs' story progress (club_story, one row per club; none = chapter 1, nothing done). */
interface StoryRepository {
    suspend fun get(clubId: UUID): StoryProgress?
    suspend fun save(progress: StoryProgress): StoryProgress
}
