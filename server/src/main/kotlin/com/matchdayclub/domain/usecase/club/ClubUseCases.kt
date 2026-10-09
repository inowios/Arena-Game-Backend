package com.matchdayclub.domain.usecase.club

import com.matchdayclub.common.time.Clock
import com.matchdayclub.common.tx.NoTx
import com.matchdayclub.common.tx.Tx
import com.matchdayclub.domain.exception.*
import com.matchdayclub.domain.model.Building
import com.matchdayclub.domain.model.BuildingState
import com.matchdayclub.domain.model.Club
import com.matchdayclub.domain.repository.BuildingRepository
import com.matchdayclub.domain.repository.ClubRepository
import com.matchdayclub.domain.rules.*
import com.matchdayclub.domain.usecase.economy.ProgressTracker
import java.time.Instant
import java.util.UUID

/** Hook for other modules to seed a new club (e.g. starter squad). */
fun interface ClubInitializer { suspend fun onClubCreated(club: Club) }

/** Fires exactly once when a construction or upgrade finishes (timer, speed-up or free ad speed-up). */
fun interface BuildingListener { suspend fun onCompleted(building: Building) }

data class ClubState(val club: Club, val buildings: List<Building>, val gridSize: Int, val now: Instant) {
    /** Constructions (first build or upgrade) occupying a builder. Decor is placed instantly and never occupies one. */
    val busyBuilders get() = buildings.count { it.state == BuildingState.UPGRADING && it.type != BuildingType.DECOR }
}

/** Shared loading + lazy finalisation of finished timers ("finalise on read"). */
class ClubService(private val clubs: ClubRepository, private val buildings: BuildingRepository, private val clock: Clock, private val listeners: List<BuildingListener> = emptyList()) {
    suspend fun clubOf(userId: UUID): Club = clubs.findByUserId(userId) ?: throw NotFoundException("Club not found")

    /** Locks the club row for the rest of the surrounding transaction. Use before changing currencies or timers. */
    suspend fun lockedClubOf(userId: UUID): Club = clubs.lockByUserId(userId) ?: throw NotFoundException("Club not found")

    suspend fun state(userId: UUID, lock: Boolean = false): ClubState {
        val club = if (lock) lockedClubOf(userId) else clubOf(userId)
        val now = clock.now()
        val list = buildings.listByClub(club.id).map { finalise(it, now) }
        return ClubState(club, list, ClubLevels.gridSize(club.level), now)
    }

    suspend fun building(club: Club, id: UUID): Building {
        val b = buildings.findById(id)?.takeIf { it.clubId == club.id } ?: throw NotFoundException("Building not found")
        return finalise(b, clock.now())
    }

    private suspend fun finalise(b: Building, now: Instant): Building = if (b.isDone(now)) complete(b, now) else b

    /** Finishes a construction now. Conditional on UPGRADING so concurrent readers never fire listeners twice. */
    suspend fun complete(b: Building, now: Instant): Building {
        val done = b.copy(state = BuildingState.IDLE, upgradeEndsAt = null, lastCollectedAt = now, updatedAt = now)
        if (buildings.completeConstruction(done)) listeners.forEach { it.onCompleted(done) }
        return done
    }

    suspend fun charge(club: Club, coins: Long = 0, gold: Int = 0): Club {
        if (coins < 0 || gold < 0) throw ValidationException("Negative charge")
        if (club.coins < coins) throw InsufficientFundsException("Not enough coins")
        if (club.gold < gold) throw InsufficientFundsException("Not enough gold")
        return clubs.update(club.copy(coins = club.coins - coins, gold = club.gold - gold, updatedAt = clock.now()))
    }

    fun requireFreeBuilder(st: ClubState) {
        if (st.busyBuilders >= st.club.builderSlots) throw ConflictException("All builders are busy")
    }
}

class CreateClubUseCase(private val clubs: ClubRepository, private val buildings: BuildingRepository, private val clock: Clock, private val initializers: List<ClubInitializer>, private val tx: Tx = NoTx) {
    private val hex = Regex("^#[0-9A-Fa-f]{6}$")

    suspend operator fun invoke(userId: UUID, name: String, primaryColor: String, secondaryColor: String, timezone: String): Club {
        if (name.trim().length !in 3..24) throw ValidationException("Club name must be 3..24 characters")
        if (!hex.matches(primaryColor) || !hex.matches(secondaryColor)) throw ValidationException("Colors must be #RRGGBB")
        runCatching { java.time.ZoneId.of(timezone) }.getOrElse { throw ValidationException("Unknown timezone") }
        return tx {
            if (clubs.findByUserId(userId) != null) throw ConflictException("Club already exists")
            val now = clock.now()
            val club = clubs.create(Club(UUID.randomUUID(), userId, name.trim(), primaryColor.uppercase(), secondaryColor.uppercase(), null, 1, 0, 2_500, 50, timezone, now, null, now, now))
            buildings.create(Building(UUID.randomUUID(), club.id, BuildingType.PITCH, 1, 2, 3, 0, BuildingState.IDLE, null, now, now, now))
            buildings.create(Building(UUID.randomUUID(), club.id, BuildingType.TICKET_OFFICE, 1, 0, 0, 0, BuildingState.IDLE, null, now, now, now))
            initializers.forEach { it.onClubCreated(club) }
            club
        }
    }
}

class GetClubUseCase(private val service: ClubService) {
    suspend operator fun invoke(userId: UUID): ClubState = service.state(userId)
}

class PlaceBuildingUseCase(private val service: ClubService, private val buildings: BuildingRepository, private val clock: Clock, private val tx: Tx = NoTx, private val cosmetics: com.matchdayclub.domain.usecase.economy.CosmeticsService? = null) {
    /** [variant]: look of a DECOR (an owned decor cosmetic); must be null for other types. */
    suspend operator fun invoke(userId: UUID, type: BuildingType, x: Int, y: Int, rotation: Int, variant: String? = null): Building = tx {
        val st = service.state(userId, lock = true)
        if (variant != null) {
            if (type != BuildingType.DECOR) throw ValidationException("Only decorations have looks")
            cosmetics?.requireOwned(st.club.id, variant, CosmeticKind.DECOR) ?: throw ValidationException("Decor looks are not available")
        }
        val spec = BuildingCatalog.spec(type)
        if (!spec.enabled) throw ValidationException("$type is not available yet")
        val lvl = spec.levels[0]
        if (st.club.level < lvl.requiredClubLevel) throw ValidationException("Requires club level ${lvl.requiredClubLevel}")
        if (st.buildings.count { it.type == type } >= spec.maxPerClub) throw ConflictException("Maximum number of $type reached")
        if (rotation !in setOf(0, 90, 180, 270)) throw ValidationException("Rotation must be 0/90/180/270")
        val fp = footprint(type, x, y, rotation)
        if (!fp.fits(st.gridSize)) throw ValidationException("Outside the club grid")
        if (st.buildings.any { footprint(it.type, it.gridX, it.gridY, it.rotation).overlaps(fp) }) throw ConflictException("Overlaps another building")
        if (lvl.buildSeconds > 0) service.requireFreeBuilder(st)
        val club = service.charge(st.club, coins = lvl.cost)
        val now = clock.now()
        val state = if (lvl.buildSeconds > 0) BuildingState.UPGRADING else BuildingState.IDLE
        buildings.create(Building(UUID.randomUUID(), club.id, type, 1, x, y, rotation, state, if (lvl.buildSeconds > 0) now.plusSeconds(lvl.buildSeconds) else null, now, now, now,
            variant = variant?.takeUnless { Cosmetics.isDefault(it) }))
    }
}

class UpgradeBuildingUseCase(private val service: ClubService, private val buildings: BuildingRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, buildingId: UUID): Building = tx {
        val st = service.state(userId, lock = true)
        val club = st.club
        val b = st.buildings.firstOrNull { it.id == buildingId } ?: throw NotFoundException("Building not found")
        if (b.state != BuildingState.IDLE) throw ConflictException("Building is under construction")
        val next = b.level + 1
        if (!BuildingCatalog.hasLevel(b.type, next)) throw ValidationException("Already at max level")
        val lvl = BuildingCatalog.level(b.type, next)
        if (club.level < lvl.requiredClubLevel) throw ValidationException("Requires club level ${lvl.requiredClubLevel}")
        service.requireFreeBuilder(st)
        service.charge(club, coins = lvl.cost)
        val now = clock.now()
        buildings.update(b.copy(level = next, state = BuildingState.UPGRADING, upgradeEndsAt = now.plusSeconds(lvl.buildSeconds), updatedAt = now))
    }
}

data class CollectResult(val building: Building, val coins: Long, val club: Club)

class CollectIncomeUseCase(private val service: ClubService, private val clubs: ClubRepository, private val buildings: BuildingRepository, private val clock: Clock, private val progress: ProgressTracker = ProgressTracker { _, _ -> }, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, buildingId: UUID): CollectResult = tx {
        val club = service.lockedClubOf(userId)
        val b = service.building(club, buildingId)
        if (b.state != BuildingState.IDLE) throw ConflictException("Building is under construction")
        val spec = BuildingCatalog.level(b.type, b.level)
        if (spec.incomePerHour == 0L) throw ValidationException("This building has no income")
        val now = clock.now()
        var amount = BuildingCatalog.income(spec, now.epochSecond - b.lastCollectedAt.epochSecond, club.level)
        val doubled = club.doubleIncomeUntil != null && now.isBefore(club.doubleIncomeUntil) && amount > 0
        if (doubled) amount *= 2
        val updatedClub = clubs.update(club.copy(coins = club.coins + amount, doubleIncomeUntil = if (doubled) null else club.doubleIncomeUntil, updatedAt = now))
        val updated = buildings.update(b.copy(lastCollectedAt = now, updatedAt = now))
        if (amount > 0) progress.record(club.id, ProgressEvent.INCOME_COLLECTED)
        CollectResult(updated, amount, updatedClub)
    }
}

class SpeedUpUseCase(private val service: ClubService, private val buildings: BuildingRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, buildingId: UUID): Building = tx {
        val club = service.lockedClubOf(userId)
        val b = service.building(club, buildingId)
        if (b.state != BuildingState.UPGRADING || b.upgradeEndsAt == null) throw ConflictException("Nothing to speed up")
        val now = clock.now()
        val cost = BuildingCatalog.speedUpGold(b.upgradeEndsAt.epochSecond - now.epochSecond)
        service.charge(club, gold = cost)
        service.complete(b, now)
    }
}

class RemoveBuildingUseCase(private val service: ClubService, private val buildings: BuildingRepository, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, buildingId: UUID) = tx {
        val club = service.lockedClubOf(userId)
        val b = service.building(club, buildingId)
        if (b.type != BuildingType.DECOR) throw ValidationException("Only decor can be removed")
        buildings.delete(b.id)
    }
}

/** Moves a placed building to another free spot (same footprint rules as placing). Free of charge. */
class MoveBuildingUseCase(private val service: ClubService, private val buildings: BuildingRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, buildingId: UUID, x: Int, y: Int, rotation: Int): Building = tx {
        val st = service.state(userId, lock = true)
        val b = st.buildings.firstOrNull { it.id == buildingId } ?: throw NotFoundException("Building not found")
        if (rotation !in setOf(0, 90, 180, 270)) throw ValidationException("Rotation must be 0/90/180/270")
        val fp = footprint(b.type, x, y, rotation)
        if (!fp.fits(st.gridSize)) throw ValidationException("Outside the club grid")
        if (st.buildings.any { it.id != b.id && footprint(it.type, it.gridX, it.gridY, it.rotation).overlaps(fp) }) throw ConflictException("Overlaps another building")
        buildings.move(b.copy(gridX = x, gridY = y, rotation = rotation, updatedAt = clock.now()))
    }
}

/** Buys the second builder (parallel construction) for gold. */
class BuyBuilderUseCase(private val service: ClubService, private val clubs: ClubRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID): Club = tx {
        val club = service.lockedClubOf(userId)
        if (club.builderSlots >= Economy.MAX_BUILDERS) throw ConflictException("All builders already hired")
        val charged = service.charge(club, gold = Economy.SECOND_BUILDER_GOLD)
        clubs.update(charged.copy(builderSlots = charged.builderSlots + 1, updatedAt = clock.now()))
    }
}

class UpdateClubUseCase(private val service: ClubService, private val clubs: ClubRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    private val hex = Regex("^#[0-9A-Fa-f]{6}$")
    suspend operator fun invoke(userId: UUID, name: String?, primaryColor: String?, secondaryColor: String?, emblemJson: String?): Club {
        if (name != null && name.trim().length !in 3..24) throw ValidationException("Club name must be 3..24 characters")
        if (primaryColor != null && !hex.matches(primaryColor)) throw ValidationException("Colors must be #RRGGBB")
        if (secondaryColor != null && !hex.matches(secondaryColor)) throw ValidationException("Colors must be #RRGGBB")
        if (emblemJson != null && emblemJson.length > 2048) throw ValidationException("Emblem too large")
        return tx {
            val club = service.lockedClubOf(userId)
            clubs.update(club.copy(
                name = name?.trim() ?: club.name, primaryColor = primaryColor?.uppercase() ?: club.primaryColor, secondaryColor = secondaryColor?.uppercase() ?: club.secondaryColor,
                emblemJson = emblemJson ?: club.emblemJson, updatedAt = clock.now(),
            ))
        }
    }
}
