package com.arenacity.data.repository

import com.arenacity.common.extension.dbQuery
import com.arenacity.data.database.BuildingTable
import com.arenacity.data.database.ClubTable
import com.arenacity.data.database.MarketPurchaseTable
import com.arenacity.data.database.ObstacleTable
import com.arenacity.data.database.ClubObstacleClock
import com.arenacity.data.database.ClubLayoutTable
import com.arenacity.data.database.ClubRoadClaimTable
import com.arenacity.domain.model.Obstacle
import com.arenacity.domain.model.ObstacleState
import com.arenacity.domain.rules.ObstacleKind
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import com.arenacity.domain.model.Building
import com.arenacity.domain.model.BuildingState
import com.arenacity.domain.model.Club
import com.arenacity.domain.repository.BuildingRepository
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.rules.BuildingType
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import java.util.UUID

class ClubRepositoryImpl : ClubRepository {
    override suspend fun findByUserId(userId: UUID) = dbQuery { ClubTable.selectAll().where { ClubTable.userId eq userId }.singleOrNull()?.toClub() }
    override suspend fun findById(id: UUID) = dbQuery { ClubTable.selectAll().where { ClubTable.id eq id }.singleOrNull()?.toClub() }
    override suspend fun findByIds(ids: Collection<UUID>) = if (ids.isEmpty()) emptyList() else dbQuery { ClubTable.selectAll().where { ClubTable.id inList ids }.map { it.toClub() } }
    override suspend fun listBots(tzBucket: Int) = dbQuery { ClubTable.selectAll().where { (ClubTable.isBot eq true) and (ClubTable.tzBucket eq tzBucket) }.map { it.toClub() } }
    override suspend fun listNear(level: Int, spread: Int, except: UUID, limit: Int) = dbQuery { ClubTable.selectAll().where { (ClubTable.level greaterEq (level - spread)) and (ClubTable.level lessEq (level + spread)) and (ClubTable.id neq except) }.limit(limit).map { it.toClub() } }
    override suspend fun lockByUserId(userId: UUID) = dbQuery { ClubTable.selectAll().where { ClubTable.userId eq userId }.forUpdate().singleOrNull()?.toClub() }
    override suspend fun lockById(id: UUID) = dbQuery { ClubTable.selectAll().where { ClubTable.id eq id }.forUpdate().singleOrNull()?.toClub() }
    override suspend fun create(club: Club) = dbQuery { ClubTable.insert { it.fill(club); it[id] = club.id; it[userId] = club.userId; it[createdAt] = club.createdAt; it[landMask] = club.landMask }; club }
    override suspend fun update(club: Club) = dbQuery { ClubTable.update({ ClubTable.id eq club.id }) { it.fill(club) }; club }
    override suspend fun setLandMask(id: UUID, mask: String) { dbQuery { ClubTable.update({ ClubTable.id eq id }) { it[landMask] = mask } } }
    override suspend fun staleLand(mask: String) = dbQuery { ClubTable.select(ClubTable.id).where { ClubTable.landMask.isNull() or (ClubTable.landMask neq mask) }.map { it[ClubTable.id] } }

    private fun UpdateBuilder<*>.fill(c: Club) {
        this[ClubTable.name] = c.name; this[ClubTable.primaryColor] = c.primaryColor; this[ClubTable.secondaryColor] = c.secondaryColor
        this[ClubTable.emblemJson] = c.emblemJson; this[ClubTable.level] = c.level; this[ClubTable.reputation] = c.reputation
        this[ClubTable.coins] = c.coins; this[ClubTable.gold] = c.gold; this[ClubTable.timezone] = c.timezone
        this[ClubTable.academyNextAt] = c.academyNextAt; this[ClubTable.medicalNextAt] = c.medicalNextAt; this[ClubTable.updatedAt] = c.updatedAt
        this[ClubTable.isBot] = c.isBot; this[ClubTable.division] = c.division; this[ClubTable.tzBucket] = c.tzBucket
        this[ClubTable.friendliesDate] = c.friendliesDate; this[ClubTable.friendliesPlayed] = c.friendliesPlayed
        this[ClubTable.loginDay] = c.loginDay; this[ClubTable.loginLast] = c.loginLast; this[ClubTable.streak] = c.streak
        this[ClubTable.doubleIncomeUntil] = c.doubleIncomeUntil; this[ClubTable.doubleIncomeAdAt] = c.doubleIncomeAdAt; this[ClubTable.freeSpeedupDate] = c.freeSpeedupDate
        this[ClubTable.builderSlots] = c.builderSlots
        this[ClubTable.seasonNo] = c.seasonNo
        this[ClubTable.kit] = c.kit; this[ClubTable.emblemFrame] = c.emblemFrame; this[ClubTable.celebration] = c.celebration
        this[ClubTable.scoutSignedDate] = c.scoutSignedDate
        this[ClubTable.trophies] = c.trophies; this[ClubTable.liveStreak] = c.liveStreak; this[ClubTable.marketSalt] = c.marketSalt; this[ClubTable.arenaChestWeek] = c.arenaChestWeek
    }

}

class BuildingRepositoryImpl : BuildingRepository {
    override suspend fun listByClub(clubId: UUID) = dbQuery { BuildingTable.selectAll().where { BuildingTable.clubId eq clubId }.orderBy(BuildingTable.createdAt).map { it.toBuilding() } }
    override suspend fun findById(id: UUID) = dbQuery { BuildingTable.selectAll().where { BuildingTable.id eq id }.singleOrNull()?.toBuilding() }
    override suspend fun create(building: Building) = dbQuery {
        BuildingTable.insert { it.fill(building); it[id] = building.id; it[clubId] = building.clubId; it[type] = building.type.name; it[gridX] = building.gridX; it[gridY] = building.gridY; it[rotation] = building.rotation; it[createdAt] = building.createdAt }
        building
    }
    override suspend fun update(building: Building) = dbQuery { BuildingTable.update({ BuildingTable.id eq building.id }) { it.fill(building) }; building }
    override suspend fun completeConstruction(building: Building): Boolean = dbQuery {
        BuildingTable.update({ (BuildingTable.id eq building.id) and (BuildingTable.state eq BuildingState.UPGRADING.name) }) { it.fill(building) } == 1
    }
    override suspend fun move(building: Building) = dbQuery {
        BuildingTable.update({ BuildingTable.id eq building.id }) { it[gridX] = building.gridX; it[gridY] = building.gridY; it[rotation] = building.rotation; it[updatedAt] = building.updatedAt }
        building
    }
    override suspend fun delete(id: UUID) { dbQuery { BuildingTable.deleteWhere { BuildingTable.id eq id } } }

    private fun UpdateBuilder<*>.fill(b: Building) {
        this[BuildingTable.level] = b.level; this[BuildingTable.state] = b.state.name; this[BuildingTable.upgradeEndsAt] = b.upgradeEndsAt
        this[BuildingTable.lastCollectedAt] = b.lastCollectedAt; this[BuildingTable.updatedAt] = b.updatedAt; this[BuildingTable.variant] = b.variant
    }

    private fun ResultRow.toBuilding() = Building(
        this[BuildingTable.id], this[BuildingTable.clubId], BuildingType.valueOf(this[BuildingTable.type]), this[BuildingTable.level],
        this[BuildingTable.gridX], this[BuildingTable.gridY], this[BuildingTable.rotation], BuildingState.valueOf(this[BuildingTable.state]),
        this[BuildingTable.upgradeEndsAt], this[BuildingTable.lastCollectedAt], this[BuildingTable.createdAt], this[BuildingTable.updatedAt], this[BuildingTable.variant],
    )
}

/** Row → club; shared with repositories that read the clubs table (live-match matchmaking). */
internal fun ResultRow.toClub() = Club(
    this[ClubTable.id], this[ClubTable.userId], this[ClubTable.name], this[ClubTable.primaryColor], this[ClubTable.secondaryColor], this[ClubTable.emblemJson],
    this[ClubTable.level], this[ClubTable.reputation], this[ClubTable.coins], this[ClubTable.gold], this[ClubTable.timezone],
    this[ClubTable.academyNextAt], this[ClubTable.medicalNextAt], this[ClubTable.createdAt], this[ClubTable.updatedAt],
    this[ClubTable.isBot], this[ClubTable.division], this[ClubTable.tzBucket], this[ClubTable.friendliesDate], this[ClubTable.friendliesPlayed],
    this[ClubTable.loginDay], this[ClubTable.loginLast], this[ClubTable.streak], this[ClubTable.doubleIncomeUntil], this[ClubTable.doubleIncomeAdAt], this[ClubTable.freeSpeedupDate],
    this[ClubTable.builderSlots], this[ClubTable.seasonNo], this[ClubTable.kit], this[ClubTable.emblemFrame], this[ClubTable.celebration],
    this[ClubTable.scoutSignedDate],
    this[ClubTable.trophies], this[ClubTable.liveStreak], this[ClubTable.marketSalt], this[ClubTable.arenaChestWeek],
    this[ClubTable.landMask],
)

class ObstacleRepositoryImpl : com.arenacity.domain.repository.ObstacleRepository {
    override suspend fun listByClub(clubId: UUID) = dbQuery { ObstacleTable.selectAll().where { ObstacleTable.clubId eq clubId }.orderBy(ObstacleTable.createdAt).map { it.toObstacle() } }
    override suspend fun create(o: Obstacle) = dbQuery {
        ObstacleTable.insert { it.fill(o); it[id] = o.id; it[clubId] = o.clubId; it[kind] = o.kind.name; it[gridX] = o.gridX; it[gridY] = o.gridY; it[rewardGold] = o.rewardGold; it[createdAt] = o.createdAt }
        o
    }
    override suspend fun update(o: Obstacle) = dbQuery { ObstacleTable.update({ ObstacleTable.id eq o.id }) { it.fill(o) }; o }
    override suspend fun finishClearing(o: Obstacle): Boolean = dbQuery {
        ObstacleTable.update({ (ObstacleTable.id eq o.id) and (ObstacleTable.state eq ObstacleState.CLEARING.name) }) { it.fill(o) } == 1
    }
    override suspend fun take(id: UUID): Boolean = dbQuery { ObstacleTable.deleteWhere { (ObstacleTable.id eq id) and (ObstacleTable.state eq ObstacleState.READY.name) } == 1 }
    override suspend fun delete(id: UUID) { dbQuery { ObstacleTable.deleteWhere { ObstacleTable.id eq id } } }
    override suspend fun spawnClock(clubId: UUID) = dbQuery { ClubObstacleClock.selectAll().where { ClubObstacleClock.id eq clubId }.singleOrNull()?.get(ClubObstacleClock.spawnedAt) }
    override suspend fun claimSpawn(clubId: UUID, expected: java.time.Instant?, next: java.time.Instant): Boolean = dbQuery {
        val cond = if (expected == null) (ClubObstacleClock.id eq clubId) and ClubObstacleClock.spawnedAt.isNull() else (ClubObstacleClock.id eq clubId) and (ClubObstacleClock.spawnedAt eq expected)
        ClubObstacleClock.update({ cond }) { it[spawnedAt] = next } == 1
    }

    private fun UpdateBuilder<*>.fill(o: Obstacle) {
        this[ObstacleTable.state] = o.state.name; this[ObstacleTable.clearEndsAt] = o.clearEndsAt; this[ObstacleTable.updatedAt] = o.updatedAt
    }

    private fun ResultRow.toObstacle() = Obstacle(
        this[ObstacleTable.id], this[ObstacleTable.clubId], ObstacleKind.valueOf(this[ObstacleTable.kind]), this[ObstacleTable.gridX], this[ObstacleTable.gridY],
        ObstacleState.valueOf(this[ObstacleTable.state]), this[ObstacleTable.clearEndsAt], this[ObstacleTable.rewardGold], this[ObstacleTable.createdAt], this[ObstacleTable.updatedAt],
    )
}

class MarketRepositoryImpl : com.arenacity.domain.repository.MarketRepository {
    override suspend fun purchased(clubId: UUID, period: Long, salt: Int): Set<Int> = dbQuery {
        MarketPurchaseTable.selectAll().where { (MarketPurchaseTable.clubId eq clubId) and (MarketPurchaseTable.period eq period) and (MarketPurchaseTable.salt eq salt) }.map { it[MarketPurchaseTable.idx] }.toSet()
    }
    override suspend fun markPurchased(clubId: UUID, period: Long, salt: Int, idx: Int, athleteId: UUID, at: java.time.Instant): Boolean = dbQuery {
        runCatching { MarketPurchaseTable.insert { it[this.clubId] = clubId; it[this.period] = period; it[this.salt] = salt; it[this.idx] = idx; it[this.athleteId] = athleteId; it[createdAt] = at } }.isSuccess
    }
}

class LayoutRepositoryImpl : com.arenacity.domain.repository.LayoutRepository {
    override suspend fun list(clubId: UUID) = dbQuery {
        ClubLayoutTable.selectAll().where { ClubLayoutTable.clubId eq clubId }.orderBy(ClubLayoutTable.slot).map {
            com.arenacity.domain.usecase.club.LayoutSlot(it[ClubLayoutTable.slot], it[ClubLayoutTable.name], decode(it[ClubLayoutTable.data]), it[ClubLayoutTable.updatedAt])
        }
    }
    override suspend fun save(clubId: UUID, layout: com.arenacity.domain.usecase.club.LayoutSlot) {
        dbQuery {
            ClubLayoutTable.deleteWhere { (ClubLayoutTable.clubId eq clubId) and (ClubLayoutTable.slot eq layout.slot) }
            ClubLayoutTable.insert { it[this.clubId] = clubId; it[slot] = layout.slot; it[name] = layout.name; it[data] = encode(layout.placements); it[updatedAt] = layout.updatedAt }
        }
    }

    companion object {
        fun encode(p: List<com.arenacity.domain.usecase.club.Placement>) = p.joinToString(";") { "${it.id},${it.x},${it.y},${it.rotation}" }
        fun decode(s: String) = s.split(";").filter { it.isNotBlank() }.mapNotNull { e ->
            val f = e.split(","); runCatching { com.arenacity.domain.usecase.club.Placement(UUID.fromString(f[0]), f[1].toInt(), f[2].toInt(), f[3].toInt()) }.getOrNull()
        }
    }
}

class RoadRepositoryImpl : com.arenacity.domain.usecase.progress.RoadRepository {
    override suspend fun claimed(clubId: UUID) = dbQuery {
        ClubRoadClaimTable.selectAll().where { ClubRoadClaimTable.clubId eq clubId }
            .groupBy({ com.arenacity.domain.rules.Road.valueOf(it[ClubRoadClaimTable.road]) }, { it[ClubRoadClaimTable.step] }).mapValues { it.value.toSet() }
    }
    override suspend fun claim(clubId: UUID, road: com.arenacity.domain.rules.Road, index: Int, at: java.time.Instant): Boolean = dbQuery {
        ClubRoadClaimTable.insertIgnore { it[this.clubId] = clubId; it[this.road] = road.name; it[step] = index; it[claimedAt] = at }.insertedCount == 1
    }
}
