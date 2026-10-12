package com.arenacity.domain.repository

import java.time.Instant
import java.util.UUID

/** The parcels of land a club has bought (the home parcels are implicit, see rules.Land.HOME). */
interface LandRepository {
    suspend fun listByClub(clubId: UUID): List<Pair<Int, Int>>
    suspend fun add(clubId: UUID, col: Int, row: Int, at: Instant)
    /** Gives a parcel back (the showcase cities shrink to the plot now that land is not for sale). */
    suspend fun remove(clubId: UUID, col: Int, row: Int)
}
