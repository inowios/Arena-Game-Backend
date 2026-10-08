package com.matchdayclub.domain.usecase.club

import com.matchdayclub.common.time.Clock
import com.matchdayclub.domain.exception.*
import com.matchdayclub.domain.model.Building
import com.matchdayclub.domain.model.BuildingState
import com.matchdayclub.domain.model.Club
import com.matchdayclub.domain.repository.BuildingRepository
import com.matchdayclub.domain.repository.ClubRepository
import com.matchdayclub.domain.rules.*
import java.time.Instant
import java.util.UUID

/** Hook for other modules to seed a new club (e.g. starter squad). */
fun interface ClubInitializer { suspend fun onClubCreated(club: Club) }

data class ClubState(val club: Club, val buildings: List<Building>, val gridSize: Int, val now: Instant)

/** Shared loading + lazy finalisation of finished timers ("finalise on read"). */
class ClubService(private val clubs: ClubRepository, private val buildings: BuildingRepository, private val clock: Clock) {
    suspend fun clubOf(userId: UUID): Club = clubs.findByUserId(userId) ?: throw NotFoundException("Club not found")

    suspend fun state(userId: UUID): ClubState {
        val club = clubOf(userId)
        val now = clock.now()
        val list = buildings.listByClub(club.id).map { finalise(it, now) }
        return ClubState(club, list, ClubLevels.gridSize(club.level), now)
    }

    suspend fun building(club: Club, id: UUID): Building {
        val b = buildings.findById(id)?.takeIf { it.clubId == club.id } ?: throw NotFoundException("Building not found")
        return finalise(b, clock.now())
    }

    private suspend fun finalise(b: Building, now: Instant): Building =
        if (b.isDone(now)) buildings.update(b.copy(state = BuildingState.IDLE, upgradeEndsAt = null, lastCollectedAt = now, updatedAt = now)) else b

    suspend fun charge(club: Club, coins: Long = 0, gold: Int = 0): Club {
        if (club.coins < coins) throw InsufficientFundsException("Not enough coins")
        if (club.gold < gold) throw InsufficientFundsException("Not enough gold")
        return clubs.update(club.copy(coins = club.coins - coins, gold = club.gold - gold, updatedAt = clock.now()))
    }
}

class CreateClubUseCase(private val clubs: ClubRepository, private val buildings: BuildingRepository, private val clock: Clock, private val initializers: List<ClubInitializer>) {
    private val hex = Regex("^#[0-9A-Fa-f]{6}$")

    suspend operator fun invoke(userId: UUID, name: String, primaryColor: String, secondaryColor: String, timezone: String): Club {
        if (name.trim().length !in 3..24) throw ValidationException("Club name must be 3..24 characters")
        if (!hex.matches(primaryColor) || !hex.matches(secondaryColor)) throw ValidationException("Colors must be #RRGGBB")
        runCatching { java.time.ZoneId.of(timezone) }.getOrElse { throw ValidationException("Unknown timezone") }
        if (clubs.findByUserId(userId) != null) throw ConflictException("Club already exists")
        val now = clock.now()
        val club = clubs.create(Club(UUID.randomUUID(), userId, name.trim(), primaryColor.uppercase(), secondaryColor.uppercase(), null, 1, 0, 2_500, 50, timezone, now, null, now, now))
        buildings.create(Building(UUID.randomUUID(), club.id, BuildingType.PITCH, 1, 2, 2, 0, BuildingState.IDLE, null, now, now, now))
        buildings.create(Building(UUID.randomUUID(), club.id, BuildingType.TICKET_OFFICE, 1, 9, 2, 0, BuildingState.IDLE, null, now, now, now))
        initializers.forEach { it.onClubCreated(club) }
        return club
    }
}

class GetClubUseCase(private val service: ClubService) {
    suspend operator fun invoke(userId: UUID): ClubState = service.state(userId)
}

class PlaceBuildingUseCase(private val service: ClubService, private val buildings: BuildingRepository, private val clock: Clock) {
    suspend operator fun invoke(userId: UUID, type: BuildingType, x: Int, y: Int, rotation: Int): Building {
        val st = service.state(userId)
        val spec = BuildingCatalog.spec(type)
        if (!spec.enabled) throw ValidationException("$type is not available yet")
        val lvl = spec.levels[0]
        if (st.club.level < lvl.requiredClubLevel) throw ValidationException("Requires club level ${lvl.requiredClubLevel}")
        if (st.buildings.count { it.type == type } >= spec.maxPerClub) throw ConflictException("Maximum number of $type reached")
        if (rotation !in setOf(0, 90, 180, 270)) throw ValidationException("Rotation must be 0/90/180/270")
        val fp = footprint(type, x, y, rotation)
        if (!fp.fits(st.gridSize)) throw ValidationException("Outside the club grid")
        if (st.buildings.any { footprint(it.type, it.gridX, it.gridY, it.rotation).overlaps(fp) }) throw ConflictException("Overlaps another building")
        val club = service.charge(st.club, coins = lvl.cost)
        val now = clock.now()
        val state = if (lvl.buildSeconds > 0) BuildingState.UPGRADING else BuildingState.IDLE
        return buildings.create(Building(UUID.randomUUID(), club.id, type, 1, x, y, rotation, state, if (lvl.buildSeconds > 0) now.plusSeconds(lvl.buildSeconds) else null, now, now, now))
    }
}

class UpgradeBuildingUseCase(private val service: ClubService, private val buildings: BuildingRepository, private val clock: Clock) {
    suspend operator fun invoke(userId: UUID, buildingId: UUID): Building {
        val club = service.clubOf(userId)
        val b = service.building(club, buildingId)
        if (b.state != BuildingState.IDLE) throw ConflictException("Building is under construction")
        val next = b.level + 1
        if (!BuildingCatalog.hasLevel(b.type, next)) throw ValidationException("Already at max level")
        val lvl = BuildingCatalog.level(b.type, next)
        if (club.level < lvl.requiredClubLevel) throw ValidationException("Requires club level ${lvl.requiredClubLevel}")
        service.charge(club, coins = lvl.cost)
        val now = clock.now()
        return buildings.update(b.copy(level = next, state = BuildingState.UPGRADING, upgradeEndsAt = now.plusSeconds(lvl.buildSeconds), updatedAt = now))
    }
}

data class CollectResult(val building: Building, val coins: Long, val club: Club)

class CollectIncomeUseCase(private val service: ClubService, private val clubs: ClubRepository, private val buildings: BuildingRepository, private val clock: Clock) {
    suspend operator fun invoke(userId: UUID, buildingId: UUID): CollectResult {
        val club = service.clubOf(userId)
        val b = service.building(club, buildingId)
        if (b.state != BuildingState.IDLE) throw ConflictException("Building is under construction")
        val spec = BuildingCatalog.level(b.type, b.level)
        if (spec.incomePerHour == 0L) throw ValidationException("This building has no income")
        val now = clock.now()
        val amount = BuildingCatalog.income(spec, now.epochSecond - b.lastCollectedAt.epochSecond, club.level)
        val updatedClub = clubs.update(club.copy(coins = club.coins + amount, updatedAt = now))
        val updated = buildings.update(b.copy(lastCollectedAt = now, updatedAt = now))
        return CollectResult(updated, amount, updatedClub)
    }
}

class SpeedUpUseCase(private val service: ClubService, private val buildings: BuildingRepository, private val clock: Clock) {
    suspend operator fun invoke(userId: UUID, buildingId: UUID): Building {
        val club = service.clubOf(userId)
        val b = service.building(club, buildingId)
        if (b.state != BuildingState.UPGRADING || b.upgradeEndsAt == null) throw ConflictException("Nothing to speed up")
        val now = clock.now()
        val cost = BuildingCatalog.speedUpGold(b.upgradeEndsAt.epochSecond - now.epochSecond)
        service.charge(club, gold = cost)
        return buildings.update(b.copy(state = BuildingState.IDLE, upgradeEndsAt = null, lastCollectedAt = now, updatedAt = now))
    }
}

class RemoveBuildingUseCase(private val service: ClubService, private val buildings: BuildingRepository) {
    suspend operator fun invoke(userId: UUID, buildingId: UUID) {
        val club = service.clubOf(userId)
        val b = service.building(club, buildingId)
        if (b.type != BuildingType.DECOR) throw ValidationException("Only decor can be removed")
        buildings.delete(b.id)
    }
}
