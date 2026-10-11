package com.matchdayclub.domain.repository

import java.time.Instant
import java.util.UUID

/** The parcels of land a club has bought (the home parcels are implicit, see rules.Land.HOME). */
interface LandRepository {
    suspend fun listByClub(clubId: UUID): List<Pair<Int, Int>>
    suspend fun add(clubId: UUID, col: Int, row: Int, at: Instant)
}
