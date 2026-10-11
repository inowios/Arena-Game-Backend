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
data class CardMatchReport(val seed: Int, val won: Boolean, val draw: Boolean, val goalsFor: Int, val goalsAgainst: Int, val cards: Map<UUID, Int>, val stake: Int = 1, val conceded: Boolean = false)
/** A real club to play against: the club and its football players (the device builds the deck the same way as its own). */
data class CardOpponent(val club: Club, val athletes: List<Athlete>)
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
        /** Coins by the result at the stake: a win pays stake times more, a loss pays stake times less; a concession pays as a plain loss. */
        fun coins(won: Boolean, draw: Boolean, stake: Int = 1, conceded: Boolean = false): Long = if (won) 120L * stake else if (draw) 50 else if (conceded) 20 else 20L / stake
    }

    /** A club of about the same level with a full football squad, chosen at random; null when nobody else is around. */
    suspend fun opponent(userId: UUID): CardOpponent? {
        val me = service.clubOf(userId)
        for (spread in listOf(1, 3, 8, 100)) {
            val near = clubs.listNear(me.level, spread, me.id, 30).shuffled()
            for (c in near) {
                val squad = athletes.listByClub(c.id).filter { it.sport == Sport.FOOTBALL && it.retiredAt == null }
                if (squad.count { it.position != com.matchdayclub.engine.model.Position.GK } >= 8 && squad.any { it.position == com.matchdayclub.engine.model.Position.GK }) return CardOpponent(c, squad)
            }
        }
        return null
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
        if (r.stake !in listOf(1, 2, 4)) throw ValidationException("Bad stake")
        val coins = coins(r.won, r.draw, r.stake, r.conceded)
        val saved = clubs.update(club.copy(coins = club.coins + coins, updatedAt = now))
        CardMatchOutcome(coins, saved, grown)
    }
}
