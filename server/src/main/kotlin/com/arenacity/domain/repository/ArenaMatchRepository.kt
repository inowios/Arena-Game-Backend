package com.arenacity.domain.repository

import com.arenacity.domain.model.ArenaMatch
import java.util.UUID

/** The Arena matches handed out (arena_matches): the result of each is taken once. */
interface ArenaMatchRepository {
    suspend fun create(match: ArenaMatch): ArenaMatch
    suspend fun find(id: UUID): ArenaMatch?
    /** The club's matches still open (handed out, no result yet). */
    suspend fun findOpen(clubId: UUID): List<ArenaMatch>
    /** Saves the status, the result and the end (status, won, finishedAt). */
    suspend fun update(match: ArenaMatch): ArenaMatch
}
