package com.arenacity.domain.model

import java.time.Instant
import java.util.UUID

/** An Arena match's life: handed out (OPEN), its result taken once (REPORTED), or left behind when the next one started (ABANDONED). */
enum class ArenaMatchStatus { OPEN, REPORTED, ABANDONED }

/**
 * One Arena match the server handed out (POST /club/arena/start; arena_matches, V59): [id] is the token the result must
 * carry, [stake] 1 / 2 / 4 (the coins multiplier), [story] = the chapter it was started as the story match of (null =
 * an ordinary match against the AI), [won] after the report.
 */
data class ArenaMatch(val id: UUID, val clubId: UUID, val stake: Int, val story: Int?, val status: ArenaMatchStatus, val startedAt: Instant,
                      val finishedAt: Instant? = null, val won: Boolean? = null)
