package com.arenacity.domain.usecase.club

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.InsufficientFundsException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.ClubZone
import com.arenacity.domain.model.ZoneSport
import com.arenacity.domain.model.ZoneState
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.repository.ZoneRepository
import com.arenacity.domain.rules.BuildingType
import com.arenacity.domain.rules.ZoneSpec
import com.arenacity.domain.rules.Zones
import java.time.Instant
import java.util.UUID

/**
 * The zone sports whose arena is open now (bought and restored): each opens the side island its sport locks for building
 * (rules.LandMap.of; ClubService.openZones and GetGroundsUseCase.openZones in DI), so GET /club and GET /club/zones agree.
 */
suspend fun openZoneSports(zones: ZoneRepository, clubId: UUID, now: Instant): Set<ZoneSport> =
    zones.listByClub(clubId).filter { it.stateAt(now) == ZoneState.OPEN }.map { it.sport }.toSet()

/**
 * A zone as the client sees it: the spec, the state now, the seconds the restoration still needs and the stadium this
 * club needs to buy it ([Zones.requiredStadium]: the zone's own, lower for a club that already owns the island's arena).
 */
data class ZoneView(val spec: ZoneSpec, val state: ZoneState, val restoreEndsAt: Instant?, val secondsLeft: Long, val requiredStadium: Int = spec.requiredStadium)

/** The stadium zones of the club's rectangle: buying one pays for the derelict arena and starts its restoration. */
class ZoneUseCases(private val service: ClubService, private val clubs: ClubRepository, private val zones: ZoneRepository, private val clock: Clock, private val tx: Tx = NoTx) {

    suspend fun list(userId: UUID): List<ZoneView> {
        val st = service.state(userId)
        return views(zones.listByClub(st.club.id), st.now, st.buildings.map { it.type }.toSet())
    }

    private suspend fun views(rows: List<ClubZone>, now: Instant, owned: Set<BuildingType>): List<ZoneView> {
        val byId = rows.associateBy { it.sport }
        return Zones.specs.values.map { spec ->
            val z = byId[spec.sport]
            val state = z?.stateAt(now) ?: ZoneState.LOCKED
            if (z != null && state != z.state) zones.upsert(z.copy(state = state))   // the restoration ended: remember it
            val left = if (state == ZoneState.RESTORING && z?.restoreEndsAt != null) maxOf(0, z.restoreEndsAt.epochSecond - now.epochSecond) else 0
            ZoneView(spec, state, z?.restoreEndsAt?.takeIf { state == ZoneState.RESTORING }, left, Zones.requiredStadium(spec.sport, owned))
        }
    }

    /**
     * Pays the zone's price and starts restoring its arena; needs the stadium level of the zone (of its arena:
     * [Zones.requiredStadium]). A zone with no island on the map (hockey) is not sold: it would open nothing.
     */
    suspend fun buy(userId: UUID, sport: ZoneSport): ZoneView = tx {
        val st = service.state(userId, lock = true)
        val spec = Zones.spec(sport)
        val now = clock.now()
        val existing = zones.listByClub(st.club.id).firstOrNull { it.sport == sport }
        if (existing != null && existing.stateAt(now) != ZoneState.LOCKED) throw ConflictException("The zone is already yours")
        if (service.lands.island(sport) == null) throw ValidationException("This zone has no island yet")
        val owned = st.buildings.map { it.type }.toSet()
        val need = Zones.requiredStadium(sport, owned)
        if (st.stadium < need) throw ValidationException("Needs stadium level {0}", need)
        if (st.club.coins < spec.priceCoins) throw InsufficientFundsException("Not enough coins")
        clubs.update(st.club.copy(coins = st.club.coins - spec.priceCoins, updatedAt = now))
        val zone = zones.upsert(ClubZone(st.club.id, sport, ZoneState.RESTORING, now, now.plusSeconds(spec.restoreSeconds)))
        views(listOf(zone), now, owned).first { it.spec.sport == sport }
    }

    /** The engine sports whose arena is open: they count as the section's venue. */
    suspend fun openSports(clubId: UUID): Set<com.arenacity.domain.model.Sport> {
        val now = clock.now()
        return zones.listByClub(clubId).filter { it.stateAt(now) == ZoneState.OPEN }.mapNotNull { Zones.spec(it.sport).engineSport }.toSet()
    }
}
