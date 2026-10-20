package com.arenacity.domain.repository

import com.arenacity.domain.model.Club
import java.time.Instant
import java.util.UUID

interface ClubRepository {
    suspend fun findByUserId(userId: UUID): Club?
    suspend fun findById(id: UUID): Club?
    suspend fun findByIds(ids: Collection<UUID>): List<Club>
    /** Whether another club ([except] aside) already has this name, without regard to case (usernames are unique). */
    suspend fun nameTaken(name: String, except: UUID? = null): Boolean
    suspend fun listBots(tzBucket: Int): List<Club>
    /** Clubs within [spread] levels of [level] other than [except], at most [limit], in no particular order. */
    suspend fun listNear(level: Int, spread: Int, except: UUID, limit: Int = 20): List<Club>
    /** SELECT … FOR UPDATE: call inside a [com.arenacity.common.tx.Tx] before read-modify-write of currencies. */
    suspend fun lockByUserId(userId: UUID): Club?
    suspend fun lockById(id: UUID): Club?
    suspend fun create(club: Club): Club
    /** Writes the club's fields; never [Club.lastSeenAt] (only [touchSeen] does). */
    suspend fun update(club: Club): Club
    /** The club of user [userId] was seen at [at] (presence; [Club.lastSeenAt]); false when the user has no club yet. */
    suspend fun touchSeen(userId: UUID, at: Instant): Boolean
}

/** The main-island spots a club opened for coins (rules.MapSpots; the stadium and the island spots are never stored here). */
interface ClubSpotRepository {
    suspend fun listByClub(clubId: UUID): Set<String>
    /** Records [spot] as open; false when it already was (the primary key keeps it once). */
    suspend fun unlock(clubId: UUID, spot: String, at: Instant): Boolean
}
