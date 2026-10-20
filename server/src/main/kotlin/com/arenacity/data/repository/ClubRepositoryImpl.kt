package com.arenacity.data.repository

import com.arenacity.common.extension.dbQuery
import com.arenacity.data.database.ClubSpotTable
import com.arenacity.data.database.ClubTable
import com.arenacity.data.database.ClubRoadClaimTable
import com.arenacity.domain.model.Club
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.repository.ClubSpotRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import java.util.UUID

class ClubRepositoryImpl : ClubRepository {
    override suspend fun findByUserId(userId: UUID) = dbQuery { ClubTable.selectAll().where { ClubTable.userId eq userId }.singleOrNull()?.toClub() }
    override suspend fun findById(id: UUID) = dbQuery { ClubTable.selectAll().where { ClubTable.id eq id }.singleOrNull()?.toClub() }
    override suspend fun nameTaken(name: String, except: UUID?) = dbQuery {
        ClubTable.selectAll().where { (ClubTable.name.lowerCase() eq name.lowercase()).let { c -> if (except != null) c and (ClubTable.id neq except) else c } }.limit(1).any()
    }
    override suspend fun findByIds(ids: Collection<UUID>) = if (ids.isEmpty()) emptyList() else dbQuery { ClubTable.selectAll().where { ClubTable.id inList ids }.map { it.toClub() } }
    override suspend fun listBots(tzBucket: Int) = dbQuery { ClubTable.selectAll().where { (ClubTable.isBot eq true) and (ClubTable.tzBucket eq tzBucket) }.map { it.toClub() } }
    override suspend fun listNear(level: Int, spread: Int, except: UUID, limit: Int) = dbQuery { ClubTable.selectAll().where { (ClubTable.level greaterEq (level - spread)) and (ClubTable.level lessEq (level + spread)) and (ClubTable.id neq except) }.limit(limit).map { it.toClub() } }
    override suspend fun lockByUserId(userId: UUID) = dbQuery { ClubTable.selectAll().where { ClubTable.userId eq userId }.forUpdate().singleOrNull()?.toClub() }
    override suspend fun lockById(id: UUID) = dbQuery { ClubTable.selectAll().where { ClubTable.id eq id }.forUpdate().singleOrNull()?.toClub() }
    override suspend fun create(club: Club) = dbQuery { ClubTable.insert { it.fill(club); it[id] = club.id; it[userId] = club.userId; it[createdAt] = club.createdAt }; club }
    override suspend fun update(club: Club) = dbQuery { ClubTable.update({ ClubTable.id eq club.id }) { it.fill(club) }; club }
    override suspend fun touchSeen(userId: UUID, at: java.time.Instant) = dbQuery { ClubTable.update({ ClubTable.userId eq userId }) { it[lastSeenAt] = at } > 0 }

    private fun UpdateBuilder<*>.fill(c: Club) {
        this[ClubTable.name] = c.name; this[ClubTable.primaryColor] = c.primaryColor; this[ClubTable.secondaryColor] = c.secondaryColor
        this[ClubTable.emblemJson] = c.emblemJson; this[ClubTable.level] = c.level; this[ClubTable.reputation] = c.reputation
        this[ClubTable.coins] = c.coins; this[ClubTable.gold] = c.gold; this[ClubTable.timezone] = c.timezone
        this[ClubTable.updatedAt] = c.updatedAt
        this[ClubTable.isBot] = c.isBot; this[ClubTable.division] = c.division; this[ClubTable.tzBucket] = c.tzBucket
        this[ClubTable.friendliesDate] = c.friendliesDate; this[ClubTable.friendliesPlayed] = c.friendliesPlayed
        this[ClubTable.loginDay] = c.loginDay; this[ClubTable.loginLast] = c.loginLast; this[ClubTable.streak] = c.streak
        this[ClubTable.seasonNo] = c.seasonNo
        this[ClubTable.kit] = c.kit; this[ClubTable.emblemFrame] = c.emblemFrame; this[ClubTable.celebration] = c.celebration
        this[ClubTable.trophies] = c.trophies; this[ClubTable.liveStreak] = c.liveStreak; this[ClubTable.arenaChestWeek] = c.arenaChestWeek
        this[ClubTable.arenaDay] = c.arenaDay; this[ClubTable.arenaRewarded] = c.arenaRewarded
        this[ClubTable.energy] = c.energy; this[ClubTable.energyAt] = c.energyAt
    }

}

class ClubSpotRepositoryImpl : ClubSpotRepository {
    override suspend fun listByClub(clubId: UUID) = dbQuery { ClubSpotTable.selectAll().where { ClubSpotTable.clubId eq clubId }.map { it[ClubSpotTable.spot] }.toSet() }
    override suspend fun unlock(clubId: UUID, spot: String, at: java.time.Instant) = dbQuery {
        ClubSpotTable.insertIgnore { it[this.clubId] = clubId; it[this.spot] = spot; it[unlockedAt] = at }.insertedCount == 1
    }
}

/** Row → club; shared with repositories that read the clubs table. */
internal fun ResultRow.toClub() = Club(
    this[ClubTable.id], this[ClubTable.userId], this[ClubTable.name], this[ClubTable.primaryColor], this[ClubTable.secondaryColor], this[ClubTable.emblemJson],
    this[ClubTable.level], this[ClubTable.reputation], this[ClubTable.coins], this[ClubTable.gold], this[ClubTable.timezone],
    this[ClubTable.createdAt], this[ClubTable.updatedAt],
    this[ClubTable.isBot], this[ClubTable.division], this[ClubTable.tzBucket], this[ClubTable.friendliesDate], this[ClubTable.friendliesPlayed],
    this[ClubTable.loginDay], this[ClubTable.loginLast], this[ClubTable.streak],
    this[ClubTable.seasonNo], this[ClubTable.kit], this[ClubTable.emblemFrame], this[ClubTable.celebration],
    this[ClubTable.trophies], this[ClubTable.liveStreak], this[ClubTable.arenaDay], this[ClubTable.arenaRewarded], this[ClubTable.arenaChestWeek],
    this[ClubTable.energy], this[ClubTable.energyAt],
    this[ClubTable.lastSeenAt],
)

class RoadRepositoryImpl : com.arenacity.domain.usecase.progress.RoadRepository {
    override suspend fun claimed(clubId: UUID) = dbQuery {
        ClubRoadClaimTable.selectAll().where { ClubRoadClaimTable.clubId eq clubId }
            .groupBy({ com.arenacity.domain.rules.Road.valueOf(it[ClubRoadClaimTable.road]) }, { it[ClubRoadClaimTable.step] }).mapValues { it.value.toSet() }
    }
    override suspend fun claim(clubId: UUID, road: com.arenacity.domain.rules.Road, index: Int, at: java.time.Instant): Boolean = dbQuery {
        ClubRoadClaimTable.insertIgnore { it[this.clubId] = clubId; it[this.road] = road.name; it[step] = index; it[claimedAt] = at }.insertedCount == 1
    }
}
