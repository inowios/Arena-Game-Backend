package com.matchdayclub.domain.repository

import com.matchdayclub.domain.model.ClubSeason
import com.matchdayclub.domain.model.Trophy
import java.util.UUID

interface SeasonRepository {
    suspend fun createSeason(season: ClubSeason): ClubSeason
    suspend fun seasonsOf(clubId: UUID, limit: Int = 50): List<ClubSeason>
    suspend fun seasonFor(clubId: UUID, leagueId: UUID): ClubSeason?
    suspend fun createTrophy(trophy: Trophy): Trophy
    suspend fun trophiesOf(clubId: UUID): List<Trophy>
}
