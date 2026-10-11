package com.matchdayclub.domain.usecase.club

import com.matchdayclub.common.time.Clock
import com.matchdayclub.common.tx.NoTx
import com.matchdayclub.common.tx.Tx
import com.matchdayclub.domain.exception.ConflictException
import com.matchdayclub.domain.exception.InsufficientFundsException
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.ClubZone
import com.matchdayclub.domain.model.ZoneSport
import com.matchdayclub.domain.model.ZoneState
import com.matchdayclub.domain.repository.ClubRepository
import com.matchdayclub.domain.repository.ZoneRepository
import com.matchdayclub.domain.rules.ZoneSpec
import com.matchdayclub.domain.rules.Zones
import java.time.Instant
import java.util.UUID

/** A zone as the client sees it: the spec, the state now and the seconds the restoration still needs. */
data class ZoneView(val spec: ZoneSpec, val state: ZoneState, val restoreEndsAt: Instant?, val secondsLeft: Long)

/** The stadium zones of the club's rectangle: buying one pays for the derelict arena and starts its restoration. */
class ZoneUseCases(private val service: ClubService, private val clubs: ClubRepository, private val zones: ZoneRepository, private val clock: Clock, private val tx: Tx = NoTx) {

    suspend fun list(userId: UUID): List<ZoneView> {
        val club = service.clubOf(userId)
        return views(zones.listByClub(club.id), clock.now())
    }

    private suspend fun views(rows: List<ClubZone>, now: Instant): List<ZoneView> {
        val byId = rows.associateBy { it.sport }
        return Zones.specs.values.map { spec ->
            val z = byId[spec.sport]
            val state = z?.stateAt(now) ?: ZoneState.LOCKED
            if (z != null && state != z.state) zones.upsert(z.copy(state = state))   // the restoration ended: remember it
            val left = if (state == ZoneState.RESTORING && z?.restoreEndsAt != null) maxOf(0, z.restoreEndsAt.epochSecond - now.epochSecond) else 0
            ZoneView(spec, state, z?.restoreEndsAt?.takeIf { state == ZoneState.RESTORING }, left)
        }
    }

    /** Pays the zone's price and starts restoring its arena; needs the stadium level of the zone. */
    suspend fun buy(userId: UUID, sport: ZoneSport): ZoneView = tx {
        val st = service.state(userId, lock = true)
        val spec = Zones.spec(sport)
        val now = clock.now()
        val existing = zones.listByClub(st.club.id).firstOrNull { it.sport == sport }
        if (existing != null && existing.stateAt(now) != ZoneState.LOCKED) throw ConflictException("The zone is already yours")
        if (st.stadium < spec.requiredStadium) throw ValidationException("Needs stadium level {0}", spec.requiredStadium)
        if (st.club.coins < spec.priceCoins) throw InsufficientFundsException("Not enough coins")
        clubs.update(st.club.copy(coins = st.club.coins - spec.priceCoins, updatedAt = now))
        val zone = zones.upsert(ClubZone(st.club.id, sport, ZoneState.RESTORING, now, now.plusSeconds(spec.restoreSeconds)))
        views(listOf(zone), now).first { it.spec.sport == sport }
    }

    /** The engine sports whose arena is open: they count as the section's venue. */
    suspend fun openSports(clubId: UUID): Set<com.matchdayclub.domain.model.Sport> {
        val now = clock.now()
        return zones.listByClub(clubId).filter { it.stateAt(now) == ZoneState.OPEN }.mapNotNull { Zones.spec(it.sport).engineSport }.toSet()
    }
}
