package com.arenacity.data.repository

import com.arenacity.common.extension.dbQuery
import com.arenacity.data.database.ClubStoryTable
import com.arenacity.domain.model.StoryProgress
import com.arenacity.domain.repository.StoryRepository
import com.arenacity.domain.rules.Story
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert
import java.util.UUID

class StoryRepositoryImpl : StoryRepository {
    override suspend fun get(clubId: UUID): StoryProgress? = dbQuery {
        ClubStoryTable.selectAll().where { ClubStoryTable.clubId eq clubId }.singleOrNull()?.let {
            StoryProgress(clubId, it[ClubStoryTable.chapter], Story.decode(it[ClubStoryTable.progress]), it[ClubStoryTable.updatedAt])
        }
    }

    override suspend fun save(progress: StoryProgress): StoryProgress = dbQuery {
        ClubStoryTable.upsert {
            it[clubId] = progress.clubId; it[chapter] = progress.chapter; it[ClubStoryTable.progress] = Story.encode(progress.progress); it[updatedAt] = progress.updatedAt
        }
        progress
    }
}
