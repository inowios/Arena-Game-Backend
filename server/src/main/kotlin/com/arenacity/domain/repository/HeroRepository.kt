package com.arenacity.domain.repository

import com.arenacity.domain.model.ClubHero
import com.arenacity.domain.model.HeroLineup
import java.util.UUID

/** The heroes clubs own (club_heroes): one row per club and hero. */
interface HeroRepository {
    suspend fun listByClub(clubId: UUID): List<ClubHero>
    /** Adds the hero unless the club already has it; true when it was added. */
    suspend fun insertIgnore(hero: ClubHero): Boolean
    /** Saves the level, the experience and the training (level, xp, xpDay, xpToday, trainingEndsAt, trainingXp) of a hero the club owns. */
    suspend fun update(hero: ClubHero): ClubHero
    /** The club's saved lineup; null = none saved (the match picks the best heroes itself). */
    suspend fun lineup(clubId: UUID): HeroLineup?
    suspend fun saveLineup(lineup: HeroLineup): HeroLineup
}
