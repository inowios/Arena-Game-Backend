package com.matchdayclub.domain.usecase.athlete

import com.matchdayclub.common.time.Clock
import com.matchdayclub.common.tx.NoTx
import com.matchdayclub.common.tx.Tx
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.Athlete
import com.matchdayclub.domain.rules.BuildingType
import com.matchdayclub.domain.model.Club
import com.matchdayclub.domain.model.Sport
import com.matchdayclub.domain.repository.AthleteRepository
import com.matchdayclub.domain.repository.ClubRepository
import com.matchdayclub.domain.rules.Growth
import com.matchdayclub.domain.rules.TrainingRules
import com.matchdayclub.domain.usecase.club.ClubService
import java.util.UUID
import kotlin.random.Random

/** What the device reports after a card match «Три зони» against the AI: the result and the experience per card. */
data class CardMatchReport(val seed: Int, val won: Boolean, val draw: Boolean, val goalsFor: Int, val goalsAgainst: Int, val cards: Map<UUID, Int>)
data class CardMatchOutcome(val coins: Long, val club: Club, val athletes: List<Athlete>)

/**
 * The card match's rewards. For now the server trusts the device's report within plausible bounds (at most
 * [MAX_CARDS] cards, at most [MAX_XP_PER_CARD] experience each, coins by the result); the engine is deterministic
 * by seed, so a replay check can replace the trust once the engine is ported.
 */
class CardMatchUseCases(private val service: ClubService, private val clubs: ClubRepository, private val athletes: AthleteRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    companion object {
        const val MAX_CARDS = 18
        const val MAX_XP_PER_CARD = 30
        fun coins(won: Boolean, draw: Boolean): Long = if (won) 120 else if (draw) 50 else 20
    }

    suspend fun report(userId: UUID, r: CardMatchReport): CardMatchOutcome = tx {
        if (r.cards.size > MAX_CARDS) throw ValidationException("Too many cards")
        if (r.goalsFor !in 0..20 || r.goalsAgainst !in 0..20) throw ValidationException("Bad score")
        val st = service.state(userId)
        val club = service.lockedClubOf(userId)
        val now = clock.now()
        val cap = TrainingRules.attributeCap(Sport.FOOTBALL, st.buildings.firstOrNull { it.type == BuildingType.PITCH }?.level ?: 1)
        val rnd = Random(r.seed.toLong() xor now.toEpochMilli())
        val mine = athletes.listByClub(club.id).associateBy { it.id }
        val grown = r.cards.mapNotNull { (id, xp) -> mine[id]?.let { a -> athletes.update(Growth.apply(a, xp.coerceIn(0, MAX_XP_PER_CARD), cap, rnd).athlete.copy(updatedAt = now)) } }
        val coins = coins(r.won, r.draw)
        val saved = clubs.update(club.copy(coins = club.coins + coins, updatedAt = now))
        CardMatchOutcome(coins, saved, grown)
    }
}
