package com.matchdayclub.data.repository

import com.matchdayclub.common.extension.dbQuery
import com.matchdayclub.data.database.ClubSeasonTable
import com.matchdayclub.data.database.TrophyTable
import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.repository.SeasonRepository
import org.jetbrains.exposed.sql.*
import java.util.UUID

class SeasonRepositoryImpl : SeasonRepository {
    override suspend fun createSeason(season: ClubSeason) = dbQuery {
        ClubSeasonTable.insert {
            it[id] = season.id; it[clubId] = season.clubId; it[leagueId] = season.leagueId; it[seasonNo] = season.seasonNo; it[division] = season.division; it[position] = season.position
            it[played] = season.played; it[won] = season.won; it[drawn] = season.drawn; it[lost] = season.lost; it[gf] = season.gf; it[ga] = season.ga; it[points] = season.points
            it[movement] = season.movement.name; it[newDivision] = season.newDivision; it[prizeCoins] = season.prizeCoins; it[prizeGold] = season.prizeGold; it[prizeReputation] = season.prizeReputation
            it[finishedAt] = season.finishedAt
        }
        season
    }
    override suspend fun seasonsOf(clubId: UUID, limit: Int) = dbQuery {
        ClubSeasonTable.selectAll().where { ClubSeasonTable.clubId eq clubId }.orderBy(ClubSeasonTable.finishedAt, SortOrder.DESC).limit(limit).map { it.toSeason() }
    }
    override suspend fun seasonFor(clubId: UUID, leagueId: UUID) = dbQuery {
        ClubSeasonTable.selectAll().where { (ClubSeasonTable.clubId eq clubId) and (ClubSeasonTable.leagueId eq leagueId) }.singleOrNull()?.toSeason()
    }
    override suspend fun createTrophy(trophy: Trophy) = dbQuery {
        TrophyTable.insert { it[id] = trophy.id; it[clubId] = trophy.clubId; it[kind] = trophy.kind.name; it[division] = trophy.division; it[seasonNo] = trophy.seasonNo; it[createdAt] = trophy.createdAt }
        trophy
    }
    override suspend fun trophiesOf(clubId: UUID) = dbQuery {
        TrophyTable.selectAll().where { TrophyTable.clubId eq clubId }.orderBy(TrophyTable.createdAt, SortOrder.DESC).map {
            Trophy(it[TrophyTable.id], it[TrophyTable.clubId], TrophyKind.valueOf(it[TrophyTable.kind]), it[TrophyTable.division], it[TrophyTable.seasonNo], it[TrophyTable.createdAt])
        }
    }

    private fun ResultRow.toSeason() = ClubSeason(
        this[ClubSeasonTable.id], this[ClubSeasonTable.clubId], this[ClubSeasonTable.leagueId], this[ClubSeasonTable.seasonNo], this[ClubSeasonTable.division], this[ClubSeasonTable.position],
        this[ClubSeasonTable.played], this[ClubSeasonTable.won], this[ClubSeasonTable.drawn], this[ClubSeasonTable.lost], this[ClubSeasonTable.gf], this[ClubSeasonTable.ga], this[ClubSeasonTable.points],
        Movement.valueOf(this[ClubSeasonTable.movement]), this[ClubSeasonTable.newDivision], this[ClubSeasonTable.prizeCoins], this[ClubSeasonTable.prizeGold], this[ClubSeasonTable.prizeReputation],
        this[ClubSeasonTable.finishedAt],
    )
}
