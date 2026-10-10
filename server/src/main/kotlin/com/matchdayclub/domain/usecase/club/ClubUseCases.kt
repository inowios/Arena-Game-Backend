package com.matchdayclub.domain.usecase.club

import com.matchdayclub.common.time.Clock
import com.matchdayclub.common.tx.NoTx
import com.matchdayclub.common.tx.Tx
import com.matchdayclub.domain.exception.*
import com.matchdayclub.domain.model.Building
import com.matchdayclub.domain.model.BuildingState
import com.matchdayclub.domain.model.Club
import com.matchdayclub.domain.model.Obstacle
import com.matchdayclub.domain.model.ObstacleState
import com.matchdayclub.domain.repository.ObstacleRepository
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

data class ClubState(val club: Club, val buildings: List<Building>, val gridSize: Int, val now: Instant, val obstacles: List<Obstacle> = emptyList()) {
    /** The stadium level whose limits apply now (the previous one while the stadium itself is being upgraded). */
    val stadium get() = Stadium.level(buildings)
    /** Constructions (first build or upgrade) occupying a builder. Decor is placed instantly and never occupies one. */
    val busyBuilders get() = buildings.count { it.state == BuildingState.UPGRADING && it.type !in Stadium.COSMETIC } + obstacles.count { it.state == ObstacleState.CLEARING }
    /** A building's footprint is free of other buildings and obstacles ([except] = the building being moved). */
    fun isFree(fp: Footprint, except: UUID? = null) =
        buildings.none { it.id != except && footprint(it.type, it.gridX, it.gridY, it.rotation).overlaps(fp) } && obstacles.none { it.footprint.overlaps(fp) }
}

/** Shared loading + lazy finalisation of finished timers ("finalise on read"). */
/** Percentages (100 = no change) the running live events apply. */
data class EventEffects(val buildTimePct: Int = 100, val incomePct: Int = 100, val obstacleGoldPct: Int = 100) {
    fun build(seconds: Long) = if (seconds <= 0) seconds else maxOf(1L, seconds * buildTimePct / 100)
}

class ClubService(private val clubs: ClubRepository, private val buildings: BuildingRepository, private val clock: Clock, private val listeners: List<BuildingListener> = emptyList(),
                  private val obstacles: ObstacleRepository? = null) {
    /** What the running live events do (shorter constructions, more income, more gold in obstacles); set by DI. */
    var effects: suspend () -> EventEffects = { EventEffects() }
    suspend fun buildSeconds(base: Long) = effects().build(base)

    /** The player's club; a retired club (account deleted or moved away) belongs to nobody any more. */
    suspend fun clubOf(userId: UUID): Club = clubs.findByUserId(userId)?.takeUnless { it.isBot } ?: throw NotFoundException("Club not found")

    /** Locks the club row for the rest of the surrounding transaction. Use before changing currencies or timers. */
    suspend fun lockedClubOf(userId: UUID): Club = clubs.lockByUserId(userId)?.takeUnless { it.isBot } ?: throw NotFoundException("Club not found")

    suspend fun state(userId: UUID, lock: Boolean = false): ClubState {
        val club = if (lock) lockedClubOf(userId) else clubOf(userId)
        val now = clock.now()
        val list = buildings.listByClub(club.id).map { finalise(it, now) }
        val grid = maxOf(ClubLevels.gridSize(club.level), Stadium.gridSize(Stadium.level(list)))
        return ClubState(club, list, grid, now, if (club.isBot) emptyList() else obstaclesOf(club, list, grid, now))
    }

    /** The club's obstacles: finished clearings turn READY, and the ones due since the last visit turn up on free cells. */
    private suspend fun obstaclesOf(club: Club, list: List<Building>, grid: Int, now: Instant): List<Obstacle> {
        val repo = obstacles ?: return emptyList()
        var current = repo.listByClub(club.id).map { o ->
            if (o.isCleared(now)) { val ready = o.copy(state = ObstacleState.READY, clearEndsAt = null, updatedAt = now); repo.finishClearing(ready); ready } else o
        }
        val clock = repo.spawnClock(club.id)
        val count = if (clock == null) Obstacles.START else Obstacles.due(clock.epochSecond, now.epochSecond, current.size)
        val next = when {
            clock == null -> now
            current.size >= Obstacles.MAX -> now                                                  // full plot: no backlog builds up
            count > 0 -> clock.plusSeconds(count * Obstacles.SPAWN_SECONDS)
            else -> null
        }
        // only the request that moves the clock spawns, so two readers never spawn twice
        if (next != null && next != clock && repo.claimSpawn(club.id, clock, next) && count > 0) {
            val r = kotlin.random.Random(club.id.mostSignificantBits xor now.epochSecond)
            repeat(count) {
                val kind = Obstacles.pick(r)
                val spot = freeSpot(kind, grid, list, current, r) ?: return@repeat
                val o = Obstacle(UUID.randomUUID(), club.id, kind, spot.first, spot.second, ObstacleState.IDLE, null, Obstacles.gold(kind, r), now, now)
                repo.create(o); current = current + o
            }
        }
        return current
    }

    private fun freeSpot(kind: ObstacleKind, grid: Int, list: List<Building>, current: List<Obstacle>, r: kotlin.random.Random): Pair<Int, Int>? {
        repeat(60) {
            val x = r.nextInt(0, grid - kind.width + 1); val y = r.nextInt(0, grid - kind.height + 1)
            val fp = Obstacles.footprint(kind, x, y)
            // keep a margin around buildings so nothing gets walled in
            val padded = Footprint(fp.x - 1, fp.y - 1, fp.w + 2, fp.h + 2)
            if (list.none { footprint(it.type, it.gridX, it.gridY, it.rotation).overlaps(padded) } && current.none { it.footprint.overlaps(fp) }) return x to y
        }
        return null
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
        if (!spec.enabled) throw ValidationException("This building is not available yet")
        val lvl = spec.levels[0]
        val have = st.buildings.count { it.type == type }
        if (have >= spec.countAt(st.stadium)) {
            // the next copy needs a bigger stadium — or there is none at all
            val next = (st.stadium + 1..Stadium.MAX_LEVEL).firstOrNull { spec.countAt(it) > have }
            if (next != null) throw ValidationException("Requires stadium level {0}", next)
            throw ConflictException("You already have the maximum number of these buildings")
        }
        if (rotation !in setOf(0, 90, 180, 270)) throw ValidationException("Rotation must be 0/90/180/270")
        val fp = footprint(type, x, y, rotation)
        if (!fp.fits(st.gridSize)) throw ValidationException("Outside the club grid")
        if (st.buildings.any { footprint(it.type, it.gridX, it.gridY, it.rotation).overlaps(fp) }) throw ConflictException("Overlaps another building")
        if (st.obstacles.any { it.footprint.overlaps(fp) }) throw ConflictException("Clear the obstacle first")
        if (lvl.buildSeconds > 0) service.requireFreeBuilder(st)
        val club = service.charge(st.club, coins = lvl.cost)
        val now = clock.now()
        val secs = service.buildSeconds(lvl.buildSeconds)
        val state = if (secs > 0) BuildingState.UPGRADING else BuildingState.IDLE
        buildings.create(Building(UUID.randomUUID(), club.id, type, 1, x, y, rotation, state, if (secs > 0) now.plusSeconds(secs) else null, now, now, now,
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
        if (b.type == BuildingType.PITCH) {
            if (club.trophies < lvl.requiredTrophies) throw ValidationException("Requires {0} trophies", lvl.requiredTrophies)
        } else if (next > BuildingCatalog.spec(b.type).capAt(st.stadium)) throw ValidationException("Requires stadium level {0}", lvl.requiredStadium)
        val instant = lvl.buildSeconds == 0L   // fences: no builder, done at once
        if (!instant) service.requireFreeBuilder(st)
        service.charge(club, coins = lvl.cost)
        val now = clock.now()
        if (instant) buildings.update(b.copy(level = next, updatedAt = now))
        else buildings.update(b.copy(level = next, state = BuildingState.UPGRADING, upgradeEndsAt = now.plusSeconds(service.buildSeconds(lvl.buildSeconds)), updatedAt = now))
    }
}

data class CollectResult(val building: Building, val coins: Long, val club: Club)

/** [collections]: income % of the club's complete cosmetic sets (on top of the atmosphere). */
class CollectIncomeUseCase(private val service: ClubService, private val clubs: ClubRepository, private val buildings: BuildingRepository, private val clock: Clock, private val progress: ProgressTracker = ProgressTracker { _, _ -> }, private val tx: Tx = NoTx,
                           private val collections: (suspend (UUID) -> Int)? = null) {
    suspend operator fun invoke(userId: UUID, buildingId: UUID): CollectResult = tx {
        val club = service.lockedClubOf(userId)
        val b = service.building(club, buildingId)
        if (b.state != BuildingState.IDLE) throw ConflictException("Building is under construction")
        val spec = BuildingCatalog.level(b.type, b.level)
        if (spec.incomePerHour == 0L) throw ValidationException("This building has no income")
        val now = clock.now()
        val bonus = Atmosphere.of(buildings.listByClub(club.id), collections?.invoke(club.id) ?: 0, service.effects().incomePct - 100)[b.id]?.total ?: 0
        var amount = BuildingCatalog.income(spec, now.epochSecond - b.lastCollectedAt.epochSecond, club.level, bonus)
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
        if (b.type !in Stadium.COSMETIC) throw ValidationException("Only decor can be removed")
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
        if (st.obstacles.any { it.footprint.overlaps(fp) }) throw ConflictException("Clear the obstacle first")
        buildings.move(b.copy(gridX = x, gridY = y, rotation = rotation, updatedAt = clock.now()))
    }
}

/** Hires another builder for gold: the 2nd any time, the 3rd once the stadium is level 5 (long constructions). */
class BuyBuilderUseCase(private val service: ClubService, private val clubs: ClubRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID): Club = tx {
        val st = service.state(userId, lock = true); val club = st.club
        if (club.builderSlots >= Economy.MAX_BUILDERS) throw ConflictException("All builders already hired")
        if (club.builderSlots >= 2 && st.stadium < Economy.THIRD_BUILDER_STADIUM) throw ValidationException("Requires stadium level {0}", Economy.THIRD_BUILDER_STADIUM)
        val charged = service.charge(club, gold = Economy.builderGold(club.builderSlots))
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

// ---------- obstacles ----------

/** Puts a free builder on an obstacle: coins now, cleared after its time. */
class ClearObstacleUseCase(private val service: ClubService, private val obstacles: ObstacleRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, obstacleId: UUID): Obstacle = tx {
        val st = service.state(userId, lock = true)
        val o = st.obstacles.firstOrNull { it.id == obstacleId } ?: throw NotFoundException("Obstacle not found")
        if (o.state != ObstacleState.IDLE) throw ConflictException("Already being cleared")
        service.requireFreeBuilder(st)
        service.charge(st.club, coins = Obstacles.cost(o.kind, st.stadium))
        val now = clock.now()
        obstacles.update(o.copy(state = ObstacleState.CLEARING, clearEndsAt = now.plusSeconds(o.kind.seconds), updatedAt = now))
    }
}

/** Finishes a clearing for gold (the same price as a construction's remaining time). */
class SpeedUpObstacleUseCase(private val service: ClubService, private val obstacles: ObstacleRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, obstacleId: UUID): Obstacle = tx {
        val st = service.state(userId, lock = true)
        val o = st.obstacles.firstOrNull { it.id == obstacleId } ?: throw NotFoundException("Obstacle not found")
        if (o.state != ObstacleState.CLEARING || o.clearEndsAt == null) throw ConflictException("Nothing to speed up")
        val now = clock.now()
        service.charge(st.club, gold = BuildingCatalog.speedUpGold(o.clearEndsAt.epochSecond - now.epochSecond))
        val ready = o.copy(state = ObstacleState.READY, clearEndsAt = null, updatedAt = now)
        obstacles.finishClearing(ready); ready
    }
}

data class ObstacleLoot(val gold: Int, val club: Club)

/** Picks up what a cleared obstacle held (once) and frees its cells. */
class CollectObstacleUseCase(private val service: ClubService, private val clubs: ClubRepository, private val obstacles: ObstacleRepository, private val clock: Clock,
                             private val progress: ProgressTracker = ProgressTracker { _, _ -> }, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, obstacleId: UUID): ObstacleLoot = tx {
        val st = service.state(userId, lock = true)
        val o = st.obstacles.firstOrNull { it.id == obstacleId } ?: throw NotFoundException("Obstacle not found")
        if (o.state != ObstacleState.READY) throw ConflictException("Not cleared yet")
        if (!obstacles.take(o.id)) throw ConflictException("Not cleared yet")
        val gold = o.rewardGold * service.effects().obstacleGoldPct / 100
        val club = clubs.update(st.club.copy(gold = st.club.gold + gold, updatedAt = clock.now()))
        progress.record(club.id, ProgressEvent.OBSTACLE_CLEARED)
        ObstacleLoot(gold, club)
    }
}

// ---------- fences ----------

/** Draws a line of fence cells in one go (a drag on the plot): every cell is checked like a placement, paid at once. */
class PlaceFencesUseCase(private val service: ClubService, private val buildings: BuildingRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, cells: List<Pair<Int, Int>>): List<Building> = tx {
        if (cells.isEmpty()) throw ValidationException("Nothing to build")
        if (cells.size > MAX_CELLS) throw ValidationException("Too many cells at once")
        if (cells.toSet().size != cells.size) throw ValidationException("Overlaps another building")
        val st = service.state(userId, lock = true)
        val spec = BuildingCatalog.spec(BuildingType.FENCE)
        val have = st.buildings.count { it.type == BuildingType.FENCE }
        if (have + cells.size > spec.countAt(st.stadium)) {
            val next = (st.stadium + 1..Stadium.MAX_LEVEL).firstOrNull { spec.countAt(it) >= have + cells.size }
            if (next != null) throw ValidationException("Requires stadium level {0}", next)
            throw ConflictException("You already have the maximum number of these buildings")
        }
        val taken = st.buildings.map { footprint(it.type, it.gridX, it.gridY, it.rotation) }
        for ((x, y) in cells) {
            val fp = Footprint(x, y, 1, 1)
            if (!fp.fits(st.gridSize)) throw ValidationException("Outside the club grid")
            if (taken.any { it.overlaps(fp) }) throw ConflictException("Overlaps another building")
            if (st.obstacles.any { it.footprint.overlaps(fp) }) throw ConflictException("Clear the obstacle first")
        }
        val club = service.charge(st.club, coins = spec.levels[0].cost * cells.size)
        val now = clock.now()
        cells.map { (x, y) -> buildings.create(Building(UUID.randomUUID(), club.id, BuildingType.FENCE, 1, x, y, 0, BuildingState.IDLE, null, now, now, now)) }
    }

    companion object { const val MAX_CELLS = 40 }
}

/** Upgrades a set of fence cells by one level each (a whole line at once), paid together; instant. */
class UpgradeFencesUseCase(private val service: ClubService, private val buildings: BuildingRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, ids: List<UUID>): List<Building> = tx {
        if (ids.isEmpty()) throw ValidationException("Nothing to build")
        val st = service.state(userId, lock = true)
        val byId = st.buildings.associateBy { it.id }
        val fences = ids.distinct().map { byId[it] ?: throw NotFoundException("Building not found") }
        if (fences.any { it.type != BuildingType.FENCE }) throw ValidationException("Only fences can be upgraded together")
        val spec = BuildingCatalog.spec(BuildingType.FENCE)
        var total = 0L
        for (f in fences) {
            val next = f.level + 1
            if (!BuildingCatalog.hasLevel(f.type, next)) throw ValidationException("Already at max level")
            if (next > spec.capAt(st.stadium)) throw ValidationException("Requires stadium level {0}", BuildingCatalog.level(f.type, next).requiredStadium)
            total += BuildingCatalog.level(f.type, next).cost
        }
        service.charge(st.club, coins = total)
        val now = clock.now()
        fences.map { buildings.update(it.copy(level = it.level + 1, updatedAt = now)) }
    }
}

// ---------- edit mode & layouts ----------

/** Where one building stands in a layout. */
data class Placement(val id: UUID, val x: Int, val y: Int, val rotation: Int)

/**
 * Edit mode: many buildings moved at once (swaps included). The whole arrangement is checked as one — inside the grid,
 * nothing overlapping, no obstacle underneath — and written in two steps so the (club, x, y) uniqueness never trips
 * mid-way. Buildings not listed stay where they are.
 */
class ApplyLayoutUseCase(private val service: ClubService, private val buildings: BuildingRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, moves: List<Placement>, partial: Boolean = false): List<Building> = tx { apply(service.state(userId, lock = true), moves, partial) }

    /** [partial]: skip listed ids that no longer exist (a saved layout older than some building changes). */
    suspend fun apply(st: ClubState, moves: List<Placement>, partial: Boolean = false): List<Building> {
        val byId = st.buildings.associateBy { it.id }
        val wanted = moves.filter { m -> byId.containsKey(m.id) || (if (partial) false else throw NotFoundException("Building not found")) }.associateBy { it.id }
        if (wanted.size != moves.count { byId.containsKey(it.id) }) throw ValidationException("A building is listed twice")
        val final = st.buildings.map { b ->
            val m = wanted[b.id]
            if (m == null) b else {
                if (m.rotation !in setOf(0, 90, 180, 270)) throw ValidationException("Rotation must be 0/90/180/270")
                b.copy(gridX = m.x, gridY = m.y, rotation = m.rotation)
            }
        }
        val fps = final.map { footprint(it.type, it.gridX, it.gridY, it.rotation) }
        fps.forEach { if (!it.fits(st.gridSize)) throw ValidationException("Outside the club grid") }
        for (i in fps.indices) for (j in i + 1 until fps.size) if (fps[i].overlaps(fps[j])) throw ConflictException("Overlaps another building")
        if (fps.any { fp -> st.obstacles.any { it.footprint.overlaps(fp) } }) throw ConflictException("Clear the obstacle first")
        val changed = final.filter { f -> byId.getValue(f.id).let { it.gridX != f.gridX || it.gridY != f.gridY || it.rotation != f.rotation } }
        if (changed.isEmpty()) return emptyList()
        val now = clock.now()
        // step 1: park the movers off the grid (unique negative cells), step 2: put them down
        changed.forEachIndexed { i, b -> buildings.move(byId.getValue(b.id).copy(gridX = -1 - i, gridY = -1, updatedAt = now)) }
        return changed.map { buildings.move(it.copy(updatedAt = now)) }
    }
}

/** A saved arrangement of the club (up to [MAX_SLOTS]): the player keeps a matchday layout and a tidy one and switches. */
data class LayoutSlot(val slot: Int, val name: String, val placements: List<Placement>, val updatedAt: java.time.Instant)

class LayoutUseCases(private val service: ClubService, private val layouts: com.matchdayclub.domain.repository.LayoutRepository, private val apply: ApplyLayoutUseCase,
                     private val clock: Clock, private val tx: Tx = NoTx) {
    suspend fun list(userId: UUID): List<LayoutSlot> = layouts.list(service.state(userId).club.id)

    /** Saves the club as it stands now into [slot]. */
    suspend fun save(userId: UUID, slot: Int, name: String): LayoutSlot = tx {
        if (slot !in 1..MAX_SLOTS) throw ValidationException("Unknown layout")
        val clean = name.trim().take(24).ifEmpty { "Layout $slot" }
        val st = service.state(userId, lock = true)
        val l = LayoutSlot(slot, clean, st.buildings.map { Placement(it.id, it.gridX, it.gridY, it.rotation) }, clock.now())
        layouts.save(st.club.id, l); l
    }

    /** Puts the club back the way [slot] has it; buildings built since stay where they are (if that still fits). */
    suspend fun applySlot(userId: UUID, slot: Int): List<Building> = tx {
        val st = service.state(userId, lock = true)
        val l = layouts.list(st.club.id).firstOrNull { it.slot == slot } ?: throw NotFoundException("Unknown layout")
        try { apply.apply(st, l.placements, partial = true) }
        catch (e: ConflictException) { throw ConflictException("This layout no longer fits: move the new buildings first") }
    }

    companion object { const val MAX_SLOTS = 3 }
}
