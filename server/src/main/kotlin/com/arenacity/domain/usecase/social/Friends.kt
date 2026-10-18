package com.arenacity.domain.usecase.social

import com.arenacity.domain.model.*
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.repository.FixtureRepository
import com.arenacity.domain.repository.LeagueRepository
import com.arenacity.domain.usecase.alliance.AllianceRepository
import com.arenacity.domain.usecase.club.ClubService
import java.util.UUID

/** A friend on the friends list: the club, its stadium level (its open spots), and whether it is one of the showcases. */
data class Friend(val club: Club, val stadium: Int, val showcase: Boolean)

/**
 * The player's friends (19 October 2026): the clubs they already know — their alliance mates, the members of their
 * private leagues and every club they played through a challenge link (either side). Bots never; the highest club
 * level first. A tap on a friend in the client challenges them to a friendly.
 */
class FriendsUseCase(
    private val clubs: ClubRepository,
    private val service: ClubService,
    private val leagues: LeagueRepository,
    private val fixtures: FixtureRepository,
    private val alliances: AllianceRepository,
) {
    suspend operator fun invoke(userId: UUID): List<Friend> {
        val me = service.clubOf(userId)
        val ids = linkedSetOf<UUID>()
        alliances.membership(me.id)?.let { m -> alliances.members(m.allianceId).forEach { ids += it.clubId } }
        leagues.listForClub(me.id).filter { it.kind == LeagueKind.PRIVATE }.forEach { ids += leagues.members(it.id) }
        fixtures.listByClub(me.id, FixtureKind.CHALLENGE, 100).forEach { ids += it.homeClubId; ids += it.awayClubId }
        ids -= me.id
        if (ids.isEmpty()) return emptyList()
        return clubs.findByIds(ids).filter { !it.isBot }
            .map { Friend(it, service.stadiumOf(it.id), false) }
            .sortedWith(compareByDescending<Friend> { it.club.level }.thenBy { it.club.name })
    }
}
