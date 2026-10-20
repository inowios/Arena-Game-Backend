package com.arenacity.testutil

import com.arenacity.domain.model.Club
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.repository.ClubSpotRepository
import java.util.UUID

class FakeClubRepository : ClubRepository {
    val rows = mutableMapOf<UUID, Club>()
    override suspend fun findByUserId(userId: UUID) = rows.values.firstOrNull { it.userId == userId }
    override suspend fun findById(id: UUID) = rows[id]
    override suspend fun findByIds(ids: Collection<UUID>) = ids.mapNotNull { rows[it] }
    override suspend fun listBots(tzBucket: Int) = rows.values.filter { it.isBot && it.tzBucket == tzBucket }
    override suspend fun listNear(level: Int, spread: Int, except: UUID, limit: Int) = rows.values.filter { it.id != except && it.level in (level - spread)..(level + spread) }.take(limit)
    override suspend fun lockByUserId(userId: UUID) = findByUserId(userId)
    override suspend fun lockById(id: UUID) = findById(id)
    override suspend fun create(club: Club): Club { rows[club.id] = club; return club }
    /** Like the real one: never writes the presence (a stale copy keeps the stored one). */
    override suspend fun update(club: Club): Club { rows[club.id] = club.copy(lastSeenAt = rows[club.id]?.lastSeenAt ?: club.lastSeenAt); return rows.getValue(club.id) }
    var touches = 0
    override suspend fun touchSeen(userId: UUID, at: java.time.Instant): Boolean { touches++; val mine = rows.values.filter { it.userId == userId }; mine.forEach { rows[it.id] = it.copy(lastSeenAt = at) }; return mine.isNotEmpty() }
}

class FakeClubSpotRepository : ClubSpotRepository {
    val rows = mutableSetOf<Pair<UUID, String>>()
    override suspend fun listByClub(clubId: UUID) = rows.filter { it.first == clubId }.map { it.second }.toSet()
    override suspend fun unlock(clubId: UUID, spot: String, at: java.time.Instant) = rows.add(clubId to spot)
    /** Tests that need a bigger stadium: opens [spots] at once (each main-island spot is one stadium level more). */
    fun open(clubId: UUID, vararg spots: String) { spots.forEach { rows.add(clubId to it) } }
    /** Opens buyable main-island spots until the stadium level is [level] (with no island open). */
    fun stadium(clubId: UUID, level: Int) = open(clubId, *com.arenacity.domain.rules.MapSpots.BUYABLE.take(level - 1).map { it.id }.toTypedArray())
}

class FakeZoneRepository : com.arenacity.domain.repository.ZoneRepository {
    val rows = mutableListOf<com.arenacity.domain.model.ClubZone>()
    override suspend fun listByClub(clubId: UUID) = rows.filter { it.clubId == clubId }
    override suspend fun upsert(zone: com.arenacity.domain.model.ClubZone) = zone.also { z -> rows.removeAll { it.clubId == z.clubId && it.people == z.people }; rows += z }
    /** The club's bridge to [people]'s island built (its island open). */
    fun open(clubId: UUID, people: com.arenacity.domain.model.People, at: java.time.Instant = java.time.Instant.EPOCH) =
        rows.add(com.arenacity.domain.model.ClubZone(clubId, people, com.arenacity.domain.model.ZoneState.OPEN, at, null))
}
