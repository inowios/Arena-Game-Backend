package com.arenacity.domain.model

import java.time.Instant
import java.util.UUID

/**
 * A club's place in the story «Іскра» (rules.Story): [chapter] = the chapter it works on (the ones before are claimed;
 * rules.Story.LAST + 1 = the season is over), [progress] = that chapter's goals (objective id → count).
 */
data class StoryProgress(val clubId: UUID, val chapter: Int, val progress: Map<String, Int>, val updatedAt: Instant)
