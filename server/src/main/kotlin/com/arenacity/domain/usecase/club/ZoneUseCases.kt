package com.arenacity.domain.usecase.club

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.InsufficientFundsException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.ClubZone
import com.arenacity.domain.model.People
import com.arenacity.domain.model.ZoneState
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.repository.ZoneRepository
import com.arenacity.domain.rules.ZoneSpec
import com.arenacity.domain.rules.Zones
import java.time.Instant
import java.util.UUID

/**
 * A people's island as the client sees it: the bridge's spec, its state now (LOCKED: no bridge; RESTORING: being built;
 * OPEN: the people returned), the seconds the bridge still needs and the stadium this club needs to start it.
 */
data class ZoneView(val spec: ZoneSpec, val state: ZoneState, val restoreEndsAt: Instant?, val secondsLeft: Long, val requiredStadium: Int = spec.requiredStadium)

/** The bridges to the peoples' islands (rules.Zones): building one pays its price; once it stands the island is open. */
class ZoneUseCases(private val service: ClubService, private val clubs: ClubRepository, private val zones: ZoneRepository, private val clock: Clock, private val tx: Tx = NoTx) {

    suspend fun list(userId: UUID): List<ZoneView> {
        val st = service.state(userId)
        return views(zones.listByClub(st.club.id), st.now)
    }

    private suspend fun views(rows: List<ClubZone>, now: Instant): List<ZoneView> {
        val byId = rows.associateBy { it.people }
        return Zones.specs.values.map { spec ->
            val z = byId[spec.people]
            val state = z?.stateAt(now) ?: ZoneState.LOCKED
            if (z != null && state != z.state) zones.upsert(z.copy(state = state))   // the bridge was finished: remember it
            val left = if (state == ZoneState.RESTORING && z?.restoreEndsAt != null) maxOf(0, z.restoreEndsAt.epochSecond - now.epochSecond) else 0
            ZoneView(spec, state, z?.restoreEndsAt?.takeIf { state == ZoneState.RESTORING }, left)
        }
    }

    /**
     * Pays the bridge's price and starts building it; needs the stadium of the people's era ([Zones]): the club's open spots
     * (rules.MapSpots). Once it stands, the island and its spot are open (one more open spot).
     */
    suspend fun buy(userId: UUID, people: People): ZoneView = tx {
        val st = service.state(userId, lock = true)
        val spec = Zones.spec(people)
        val now = clock.now()
        val existing = zones.listByClub(st.club.id).firstOrNull { it.people == people }
        if (existing != null && existing.stateAt(now) != ZoneState.LOCKED) throw ConflictException("The zone is already yours")
        if (st.stadium < spec.requiredStadium) throw ValidationException("Needs stadium level {0}", spec.requiredStadium)
        if (st.club.coins < spec.priceCoins) throw InsufficientFundsException("Not enough coins")
        clubs.update(st.club.copy(coins = st.club.coins - spec.priceCoins, updatedAt = now))
        val zone = zones.upsert(ClubZone(st.club.id, people, ZoneState.RESTORING, now, now.plusSeconds(spec.restoreSeconds)))
        views(listOf(zone), now).first { it.spec.people == people }
    }
}
