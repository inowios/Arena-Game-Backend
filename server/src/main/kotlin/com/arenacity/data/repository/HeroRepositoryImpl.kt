package com.arenacity.data.repository

import com.arenacity.common.extension.dbQuery
import com.arenacity.data.database.ClubHeroTable
import com.arenacity.domain.model.ClubHero
import com.arenacity.domain.model.HeroSource
import com.arenacity.domain.repository.HeroRepository
import org.jetbrains.exposed.sql.*
import java.util.UUID

class HeroRepositoryImpl : HeroRepository {
    override suspend fun listByClub(clubId: UUID) = dbQuery {
        ClubHeroTable.selectAll().where { ClubHeroTable.clubId eq clubId }.orderBy(ClubHeroTable.heroId).map { it.toHero() }
    }

    override suspend fun insertIgnore(hero: ClubHero): Boolean = dbQuery {
        ClubHeroTable.insertIgnore {
            it[clubId] = hero.clubId; it[heroId] = hero.heroId; it[level] = hero.level; it[xp] = hero.xp; it[xpDay] = hero.xpDay; it[xpToday] = hero.xpToday
            it[heroSource] = hero.source.name; it[unlockedAt] = hero.unlockedAt
        }.insertedCount > 0
    }

    override suspend fun update(hero: ClubHero): ClubHero = dbQuery {
        ClubHeroTable.update({ (ClubHeroTable.clubId eq hero.clubId) and (ClubHeroTable.heroId eq hero.heroId) }) {
            it[level] = hero.level; it[xp] = hero.xp; it[xpDay] = hero.xpDay; it[xpToday] = hero.xpToday
        }
        hero
    }

    private fun ResultRow.toHero() = ClubHero(
        this[ClubHeroTable.clubId], this[ClubHeroTable.heroId], this[ClubHeroTable.level], this[ClubHeroTable.xp], HeroSource.valueOf(this[ClubHeroTable.heroSource]),
        this[ClubHeroTable.unlockedAt], this[ClubHeroTable.xpDay], this[ClubHeroTable.xpToday],
    )
}
