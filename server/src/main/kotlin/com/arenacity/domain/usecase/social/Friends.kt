package com.arenacity.domain.usecase.social

import com.arenacity.common.time.Clock
import com.arenacity.domain.model.*
import com.arenacity.domain.repository.BuildingRepository
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.repository.LandRepository
import com.arenacity.domain.repository.UserRepository
import com.arenacity.domain.rules.Showcase
import com.arenacity.domain.rules.Stadium
import java.util.UUID

/**
 * The two showcase friends every player has (rules.Showcase): bot clubs outside every league (tzBucket −1), their cities
 * rebuilt from the layout at start-up whenever the layout changed, so they always show what the current game allows
 * (the 48×48 plot of the archipelago v2 rebuilds the older 24×24 and whole-field towns on the first start).
 */
class ShowcaseService(
    private val users: UserRepository,
    private val clubs: ClubRepository,
    private val buildings: BuildingRepository,
    private val land: LandRepository,
    private val clock: Clock,
) {
    data class Def(val id: UUID, val name: String, val stadium: Int, val level: Int, val reputation: Int, val primary: String, val secondary: String)

    companion object {
        const val BUCKET = -1   // no league ever draws bots from here
        val DEFS = listOf(
            Def(UUID.nameUUIDFromBytes("arenacity-showcase-5".toByteArray()), "Nova Harbor", 5, 6, 3_200, "#1668FF", "#FFFFFF"),
            Def(UUID.nameUUIDFromBytes("arenacity-showcase-10".toByteArray()), "Aurora Prime", 10, 10, 21_000, "#F2B01E", "#14171C"),
        )
        val IDS = DEFS.map { it.id }.toSet()
    }

    suspend fun ensure() { for (d in DEFS) ensure(d) }

    private suspend fun ensure(d: Def) {
        val now = clock.now()
        val city = Showcase.city(d.stadium)
        if (clubs.findById(d.id) == null) {
            val userId = UUID.nameUUIDFromBytes("arenacity-showcase-user-${d.stadium}".toByteArray())
            if (users.findById(userId) == null) users.create(User(userId, AuthProvider.BOT, "showcase-${d.stadium}", null, null, null, true, now, now))
            clubs.create(Club(d.id, userId, d.name, d.primary, d.secondary, null, d.level, d.reputation, 0, 0, "UTC", null, null, now, now,
                isBot = true, division = 1, tzBucket = BUCKET))
        }
        // the land: the parcels the layout needs beyond the plot (none since the archipelago: the plot is the sixteen home
        // parcels, never stored), every other stored row given back — older layouts owned the whole 6×6 field
        val owned = land.listByClub(d.id).toSet()
        val need = city.parcels - com.arenacity.domain.rules.Land.HOME
        for ((c, r) in need - owned) land.add(d.id, c, r, now)
        for ((c, r) in owned - need) land.remove(d.id, c, r)
        // the buildings: replaced only when the layout differs from what is stored
        val have = buildings.listByClub(d.id)
        val want = city.slots.map { Triple(it.type, it.level, it.x to it.y) }.toSet()
        if (have.size == city.slots.size && have.map { Triple(it.type, it.level, it.gridX to it.gridY) }.toSet() == want) return
        for (b in have) buildings.delete(b.id)
        for (s in city.slots) buildings.create(Building(UUID.randomUUID(), d.id, s.type, s.level, s.x, s.y, 0, BuildingState.IDLE, null, now, now, now))
    }
}

/** A friend on the friends list: the club, its stadium level, and whether it is one of the showcase cities. */
data class Friend(val club: Club, val stadium: Int, val showcase: Boolean)

/** The player's friends: for now the two showcase cities (real friends come later). */
class FriendsUseCase(private val clubs: ClubRepository, private val buildings: BuildingRepository) {
    suspend operator fun invoke(): List<Friend> = ShowcaseService.DEFS.mapNotNull { d ->
        clubs.findById(d.id)?.let { Friend(it, Stadium.level(buildings.listByClub(it.id)), true) }
    }
}
