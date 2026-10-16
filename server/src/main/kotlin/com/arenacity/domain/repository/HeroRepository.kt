package com.arenacity.domain.repository

import com.arenacity.domain.model.ClubHero
import java.util.UUID

/** The heroes clubs own (club_heroes): one row per club and hero. */
interface HeroRepository {
    suspend fun listByClub(clubId: UUID): List<ClubHero>
    /** Adds the hero unless the club already has it; true when it was added. */
    suspend fun insertIgnore(hero: ClubHero): Boolean
    /** Saves the level and the experience (level, xp, xpDay, xpToday) of a hero the club owns. */
    suspend fun update(hero: ClubHero): ClubHero
}
