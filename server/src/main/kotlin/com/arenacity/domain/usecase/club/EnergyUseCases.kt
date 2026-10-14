package com.arenacity.domain.usecase.club

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.Club
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.rules.Energy
import com.arenacity.domain.rules.EnergyState
import java.util.UUID

/** The club's energy (rules.Energy): what it has now, refilling it for gems, and spending it as a match starts. */
class EnergyUseCases(private val service: ClubService, private val clubs: ClubRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend fun state(userId: UUID): EnergyState = Energy.state(service.clubOf(userId), clock.now())

    /** Fills the energy up for [Energy.REFILL_GOLD] gems. */
    suspend fun refill(userId: UUID): Pair<Club, EnergyState> = tx {
        val club = service.lockedClubOf(userId); val now = clock.now()
        if (Energy.state(club, now).current >= Energy.MAX) throw ConflictException("Energy is already full")
        if (club.gold < Energy.REFILL_GOLD) throw ValidationException("Not enough gold")
        val saved = clubs.update(Energy.fill(club, now).copy(gold = club.gold - Energy.REFILL_GOLD, updatedAt = now))
        saved to Energy.state(saved, now)
    }

    companion object {
        /** [club] with [cost] energy spent at [now]; a clear 400 when it has too little. */
        fun spend(club: Club, cost: Int, now: java.time.Instant): Club =
            Energy.spend(club, cost, now) ?: throw ValidationException("Not enough energy")
    }
}
