package com.arenacity.domain.usecase.social

import com.arenacity.domain.model.*
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.usecase.club.ClubService

/** A friend on the friends list: the club, its stadium level (its open spots), and whether it is one of the showcases. */
data class Friend(val club: Club, val stadium: Int, val showcase: Boolean)

/**
 * The player's friends: none yet (real friends come later). The two showcase bot clubs that showed what a city could
 * become went with the city builder (the owner, 18 October 2026; V55 removes them).
 */
class FriendsUseCase(private val clubs: ClubRepository, private val service: ClubService) {
    suspend operator fun invoke(): List<Friend> = emptyList()
}
