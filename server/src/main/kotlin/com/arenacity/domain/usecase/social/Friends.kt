package com.arenacity.domain.usecase.social

import com.arenacity.common.time.Clock
import com.arenacity.domain.model.*
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.repository.ClubSpotRepository
import com.arenacity.domain.repository.UserRepository
import com.arenacity.domain.repository.ZoneRepository
import com.arenacity.domain.rules.MapSpots
import com.arenacity.domain.usecase.club.ClubService
import java.util.UUID

/**
 * The two showcase friends every player has (the owner, 12 October: «one with a level-5 stadium, one built to the
 * maximum — so players see what their club can become»): bot clubs outside every league (tzBucket −1). On the five
 * islands map (17 October 2026) a showcase is its open spots: [Def.spots] the main-island spots it has, [Def.peoples] the
 * islands it has open (their zones OPEN). Refreshed at start-up, so they always show what the current map allows.
 */
class ShowcaseService(
    private val users: UserRepository,
    private val clubs: ClubRepository,
    private val spots: ClubSpotRepository,
    private val zones: ZoneRepository,
    private val clock: Clock,
) {
    /** [stadium]: the stadium level its open spots make (1 + [spots] + [peoples]). */
    data class Def(val id: UUID, val name: String, val stadium: Int, val level: Int, val reputation: Int, val primary: String, val secondary: String,
                   val spots: Set<String>, val peoples: Set<People>) {
        init { require(MapSpots.stadiumLevel(MapSpots.open(spots, peoples).size) == stadium) { "showcase $name: its spots make another stadium level" } }
    }

    companion object {
        const val BUCKET = -1   // no league ever draws bots from here
        val DEFS = listOf(
            Def(UUID.nameUUIDFromBytes("arenacity-showcase-5".toByteArray()), "Nova Harbor", 5, 6, 3_200, "#1668FF", "#FFFFFF",
                setOf(MapSpots.ACADEMY, MapSpots.HOSPITAL, MapSpots.MALL), setOf(People.BEAST)),
            Def(UUID.nameUUIDFromBytes("arenacity-showcase-10".toByteArray()), "Aurora Prime", 10, 10, 21_000, "#F2B01E", "#14171C",
                MapSpots.BUYABLE.map { it.id }.toSet(), People.entries.toSet()),
        )
        val IDS = DEFS.map { it.id }.toSet()
    }

    suspend fun ensure() { for (d in DEFS) ensure(d) }

    private suspend fun ensure(d: Def) {
        val now = clock.now()
        if (clubs.findById(d.id) == null) {
            val userId = UUID.nameUUIDFromBytes("arenacity-showcase-user-${d.stadium}".toByteArray())
            if (users.findById(userId) == null) users.create(User(userId, AuthProvider.BOT, "showcase-${d.stadium}", null, null, null, true, now, now))
            clubs.create(Club(d.id, userId, d.name, d.primary, d.secondary, null, d.level, d.reputation, 0, 0, "UTC", null, null, now, now,
                isBot = true, division = 1, tzBucket = BUCKET))
        }
        // its main-island spots (unlock is idempotent: an open one stays)
        for (s in d.spots) spots.unlock(d.id, s, now)
        // its islands: their zones OPEN (restored at once), the others locked
        val have = zones.listByClub(d.id).associateBy { it.people }
        for (people in People.entries) {
            val state = if (people in d.peoples) ZoneState.OPEN else ZoneState.LOCKED
            if (have[people]?.state != state && (state == ZoneState.OPEN || have[people] != null)) zones.upsert(ClubZone(d.id, people, state, now, null))
        }
    }
}

/** A friend on the friends list: the club, its stadium level (its open spots), and whether it is one of the showcases. */
data class Friend(val club: Club, val stadium: Int, val showcase: Boolean)

/** The player's friends: for now the two showcases (real friends come later). */
class FriendsUseCase(private val clubs: ClubRepository, private val service: ClubService) {
    suspend operator fun invoke(): List<Friend> = ShowcaseService.DEFS.mapNotNull { d ->
        clubs.findById(d.id)?.let { Friend(it, service.stadiumOf(it.id), true) }
    }
}
