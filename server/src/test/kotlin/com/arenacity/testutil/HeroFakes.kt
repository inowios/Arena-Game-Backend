package com.arenacity.testutil

import com.arenacity.domain.model.ClubHero
import com.arenacity.domain.model.HeroLineup
import com.arenacity.domain.repository.HeroRepository
import java.util.UUID

class FakeHeroRepository : HeroRepository {
    val rows = linkedMapOf<Pair<UUID, String>, ClubHero>()
    override suspend fun listByClub(clubId: UUID) = rows.values.filter { it.clubId == clubId }.sortedBy { it.heroId }
    override suspend fun insertIgnore(hero: ClubHero): Boolean {
        val key = hero.clubId to hero.heroId
        if (key in rows) return false
        rows[key] = hero; return true
    }
    override suspend fun update(hero: ClubHero): ClubHero { check(hero.clubId to hero.heroId in rows); rows[hero.clubId to hero.heroId] = hero; return hero }
    val lineups = linkedMapOf<UUID, HeroLineup>()
    override suspend fun lineup(clubId: UUID) = lineups[clubId]
    override suspend fun saveLineup(lineup: HeroLineup): HeroLineup { lineups[lineup.clubId] = lineup; return lineup }
}
