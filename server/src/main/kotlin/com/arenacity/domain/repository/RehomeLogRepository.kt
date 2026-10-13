package com.arenacity.domain.repository

import java.time.Instant
import java.util.UUID

/**
 * One thing the land migration moved (ClubService.settle): a building from ([oldX], [oldY]) to ([newX], [newY]) — null when
 * it stayed parked for lack of room —, an obstacle taken away (null, with the gold it held given to the club), or a road
 * or fence tile off the land deleted ([REMOVED], null: its [level] and the [coins] its levels cost refunded).
 */
data class RehomeMove(val clubId: UUID, val itemId: UUID, val kind: String, val type: String, val oldX: Int, val oldY: Int, val oldRotation: Int,
                      val newX: Int?, val newY: Int?, val newRotation: Int, val gold: Int, val maskId: String, val at: Instant,
                      val level: Int = 0, val coins: Long = 0) {
    companion object { const val BUILDING = "BUILDING"; const val OBSTACLE = "OBSTACLE"; const val REMOVED = "REMOVED" }
}

/** The land migration's log (rehome_log): every move onto a new land mask, so a revert can put the town back. */
interface RehomeLogRepository {
    suspend fun record(moves: List<RehomeMove>)
}
