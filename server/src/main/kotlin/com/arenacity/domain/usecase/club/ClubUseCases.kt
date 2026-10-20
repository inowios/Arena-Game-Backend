package com.arenacity.domain.usecase.club

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.exception.*
import com.arenacity.domain.model.Club
import com.arenacity.domain.model.People
import com.arenacity.domain.model.ZoneState
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.repository.ClubSpotRepository
import com.arenacity.domain.repository.ZoneRepository
import com.arenacity.domain.rules.MapSpots
import java.time.Instant
import java.util.UUID

/** Hook for other modules to seed a new club (e.g. starter squad). */
fun interface ClubInitializer { suspend fun onClubCreated(club: Club) }

/**
 * What a club has open on the five islands map (rules.MapSpots): [unlocked] = the main-island spots it bought, [zones] =
 * the state of each people's island now (LOCKED when the club never started it).
 */
data class ClubSpots(val unlocked: Set<String>, val zones: Map<People, ZoneState>) {
    /** The peoples whose island is open (their zone OPEN). */
    val peoples: Set<People> get() = zones.filterValues { it == ZoneState.OPEN }.keys
    /** The open spot ids, in the map's order: the stadium, the bought main spots, the open islands' spots. */
    val open: List<String> by lazy { MapSpots.open(unlocked, peoples) }
    /** The club's stadium level: the number of open spots (1..10). */
    val stadium: Int get() = MapSpots.stadiumLevel(open.size)
    fun isOpen(id: String) = id in open

    companion object { val NONE = ClubSpots(emptySet(), emptyMap()) }
}

/** A club as its owner sees it now. */
data class ClubState(val club: Club, val now: Instant, val spots: ClubSpots = ClubSpots.NONE) {
    /** The stadium level: the number of open spots (rules.MapSpots). */
    val stadium: Int get() = spots.stadium
    fun isOpen(spot: String) = spots.isOpen(spot)
}

/**
 * The club and what it has open. [spots]: the main-island spots bought (none when null: tests); [zones]: the peoples'
 * islands (none open when null).
 */
class ClubService(private val clubs: ClubRepository, private val clock: Clock, private val spots: ClubSpotRepository? = null, private val zones: ZoneRepository? = null) {
    /** The player's club; a retired club (account deleted or moved away) belongs to nobody any more. */
    suspend fun clubOf(userId: UUID): Club = clubs.findByUserId(userId)?.takeUnless { it.isBot } ?: throw NotFoundException("Club not found")

    /** Locks the club row for the rest of the surrounding transaction. Use before changing currencies or timers. */
    suspend fun lockedClubOf(userId: UUID): Club = clubs.lockByUserId(userId)?.takeUnless { it.isBot } ?: throw NotFoundException("Club not found")

    suspend fun state(userId: UUID, lock: Boolean = false): ClubState {
        val club = if (lock) lockedClubOf(userId) else clubOf(userId)
        val now = clock.now()
        return ClubState(club, now, spotsOf(club.id, now))
    }

    /** What club [clubId] (any club: a visited one, a bot) has open at [now]. */
    suspend fun spotsOf(clubId: UUID, now: Instant = clock.now()): ClubSpots {
        val bought = spots?.listByClub(clubId).orEmpty().filter { id -> MapSpots.BUYABLE.any { it.id == id } }.toSet()
        val islands = zones?.listByClub(clubId).orEmpty().associate { it.people to it.stateAt(now) }
        return ClubSpots(bought, People.entries.associateWith { islands[it] ?: ZoneState.LOCKED })
    }

    /** The stadium level of club [clubId]: its open spots. */
    suspend fun stadiumOf(clubId: UUID): Int = spotsOf(clubId).stadium

    suspend fun charge(club: Club, coins: Long = 0, gold: Int = 0): Club {
        if (coins < 0 || gold < 0) throw ValidationException("Negative charge")
        if (club.coins < coins) throw InsufficientFundsException("Not enough coins")
        if (club.gold < gold) throw InsufficientFundsException("Not enough gold")
        return clubs.update(club.copy(coins = club.coins - coins, gold = club.gold - gold, updatedAt = clock.now()))
    }
}

class CreateClubUseCase(private val clubs: ClubRepository, private val clock: Clock, private val initializers: List<ClubInitializer>, private val tx: Tx = NoTx) {
    companion object { const val DEFAULT_EMBLEM = """{"crest":1}""" }
    private val hex = Regex("^#[0-9A-Fa-f]{6}$")

    suspend operator fun invoke(userId: UUID, rawName: String, primaryColor: String, secondaryColor: String, timezone: String): Club {
        val name = com.arenacity.domain.rules.ClubNames.normalize(rawName)
        com.arenacity.domain.rules.ClubNames.problem(name)?.let { throw ValidationException(it) }
        if (!hex.matches(primaryColor) || !hex.matches(secondaryColor)) throw ValidationException("Colors must be #RRGGBB")
        runCatching { java.time.ZoneId.of(timezone) }.getOrElse { throw ValidationException("Unknown timezone") }
        return tx {
            if (clubs.findByUserId(userId) != null) throw ConflictException("Club already exists")
            val now = clock.now()
            // the stadium is open from the start (rules.MapSpots): nothing to store for it
            // every club starts with crest 1 of the 15 painted ones and picks another in its profile (emblemJson {"crest": N})
            val club = clubs.create(Club(UUID.randomUUID(), userId, name.trim(), primaryColor.uppercase(), secondaryColor.uppercase(), DEFAULT_EMBLEM, 1, 0, 2_500, 50, timezone, now, now))
            initializers.forEach { it.onClubCreated(club) }
            club
        }
    }
}

class GetClubUseCase(private val service: ClubService) {
    suspend operator fun invoke(userId: UUID): ClubState = service.state(userId)
}

/**
 * Opens a main-island spot for its coins (rules.MapSpots), at once and in any order. The stadium is always open and an
 * island's spot opens with its island (the zone: ZoneUseCases), never here.
 */
class UnlockSpotUseCase(private val service: ClubService, private val spots: ClubSpotRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, spotId: String): ClubState = tx {
        val spot = MapSpots.find(spotId) ?: throw NotFoundException("Unknown spot")
        val st = service.state(userId, lock = true)
        if (st.isOpen(spot.id)) throw ConflictException("The spot is already open")
        if (spot.people != null) throw ValidationException("This spot opens with its island")
        val club = service.charge(st.club, coins = spot.cost)
        if (!spots.unlock(club.id, spot.id, clock.now())) throw ConflictException("The spot is already open")
        service.state(userId)
    }
}

class UpdateClubUseCase(private val service: ClubService, private val clubs: ClubRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    private val hex = Regex("^#[0-9A-Fa-f]{6}$")
    suspend operator fun invoke(userId: UUID, rawName: String?, primaryColor: String?, secondaryColor: String?, emblemJson: String?): Club {
        val name = rawName?.let { com.arenacity.domain.rules.ClubNames.normalize(it) }
        if (name != null) com.arenacity.domain.rules.ClubNames.problem(name)?.let { throw ValidationException(it) }
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
