package com.arenacity.domain.usecase.athlete

import com.arenacity.common.time.Clock
import com.arenacity.domain.model.Athlete
import com.arenacity.domain.model.Club
import com.arenacity.domain.model.Sport
import com.arenacity.domain.repository.AthleteRepository
import com.arenacity.domain.rules.AthleteFactory
import com.arenacity.domain.usecase.club.ClubInitializer

/**
 * The athletes («вихованці академії»): the pupils that fill a club's eleven around its heroes in the simulated matches
 * (the league, the cup, friendlies, challenges; rules.HeroSquad). The club never manages them any more (the squad,
 * academy, trainings, hospital, scouting, techniques and the transfer market went on 20 October 2026): a new club gets
 * its starter squad here and the season's end ages it (CareerService).
 */
class StarterSquadInitializer(private val athletes: AthleteRepository, private val clock: Clock) : ClubInitializer {
    override suspend fun onClubCreated(club: Club) = athletes.createAll(AthleteFactory.starterSquad(club.id, clock.now()))
}

/**
 * Which sport sections a club fields: enough athletes for a lineup. Football only since 15 October 2026 (rules.Sections;
 * the basketball and tennis arenas that opened the other two went), and football needs no building (it plays in the
 * stadium, always open on the map).
 */
class SectionService(private val athletes: AthleteRepository) {
    suspend fun active(club: Club, squad: List<Athlete>? = null): List<Sport> {
        val list = squad ?: athletes.listByClub(club.id)
        return com.arenacity.domain.rules.Sections.ORDER.filter { s -> list.count { it.sport == s } >= com.arenacity.domain.rules.Sections.profile(s).startersCount }
    }
}
