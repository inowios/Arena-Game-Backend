package com.matchdayclub.domain.repository

import com.matchdayclub.domain.model.Building
import com.matchdayclub.domain.model.Club
import java.util.UUID

interface ClubRepository {
    suspend fun findByUserId(userId: UUID): Club?
    suspend fun findById(id: UUID): Club?
    suspend fun findByIds(ids: Collection<UUID>): List<Club>
    suspend fun listBots(tzBucket: Int): List<Club>
    /** Clubs within [spread] levels of [level] other than [except], at most [limit], in no particular order. */
    suspend fun listNear(level: Int, spread: Int, except: UUID, limit: Int = 20): List<Club>
    /** SELECT … FOR UPDATE: call inside a [com.matchdayclub.common.tx.Tx] before read-modify-write of currencies. */
    suspend fun lockByUserId(userId: UUID): Club?
    suspend fun lockById(id: UUID): Club?
    suspend fun create(club: Club): Club
    suspend fun update(club: Club): Club
}

interface BuildingRepository {
    suspend fun listByClub(clubId: UUID): List<Building>
    suspend fun findById(id: UUID): Building?
    suspend fun create(building: Building): Building
    suspend fun update(building: Building): Building
    /** Marks a construction finished only if it is still UPGRADING; true when this call did it (listeners fire once). */
    suspend fun completeConstruction(building: Building): Boolean
    /** Updates grid position and rotation only. */
    suspend fun move(building: Building): Building
    suspend fun delete(id: UUID)
}

interface ObstacleRepository {
    suspend fun listByClub(clubId: UUID): List<com.matchdayclub.domain.model.Obstacle>
    suspend fun create(o: com.matchdayclub.domain.model.Obstacle): com.matchdayclub.domain.model.Obstacle
    suspend fun update(o: com.matchdayclub.domain.model.Obstacle): com.matchdayclub.domain.model.Obstacle
    /** Moves CLEARING → READY only if still CLEARING (concurrent readers finish it once). */
    suspend fun finishClearing(o: com.matchdayclub.domain.model.Obstacle): Boolean
    /** Deletes a READY obstacle; false if someone else picked it up first. */
    suspend fun take(id: UUID): Boolean
    /** When the last obstacle turned up for the club (null: never). */
    suspend fun spawnClock(clubId: UUID): java.time.Instant?
    /** Moves the spawn clock from [expected] to [next]; false if another request already did (it spawns, we don't). */
    suspend fun claimSpawn(clubId: UUID, expected: java.time.Instant?, next: java.time.Instant): Boolean
}

/** Saved club layouts (edit mode), up to a few slots per club. */
interface LayoutRepository {
    suspend fun list(clubId: UUID): List<com.matchdayclub.domain.usecase.club.LayoutSlot>
    suspend fun save(clubId: UUID, layout: com.matchdayclub.domain.usecase.club.LayoutSlot)
}
