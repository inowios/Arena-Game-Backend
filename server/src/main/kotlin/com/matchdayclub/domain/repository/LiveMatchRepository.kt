package com.matchdayclub.domain.repository

import com.matchdayclub.domain.model.LiveMatch
import com.matchdayclub.domain.model.LiveStatus
import java.util.UUID

interface LiveMatchRepository {
    suspend fun create(m: LiveMatch): LiveMatch
    suspend fun find(id: UUID): LiveMatch?
    suspend fun findOpen(clubId: UUID): List<LiveMatch>
    suspend fun update(m: LiveMatch): LiveMatch
    /** Human clubs within a trophy band, for matchmaking (never bots, never the club itself). */
    suspend fun opponents(clubId: UUID, minTrophies: Int, maxTrophies: Int, limit: Int): List<com.matchdayclub.domain.model.Club>
    /** Recent finished matches of a club, newest first. */
    suspend fun recent(clubId: UUID, limit: Int): List<LiveMatch>
    fun statusOf(s: String) = LiveStatus.valueOf(s)
}
