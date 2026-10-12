package com.arenacity.domain.usecase.admin

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.Club
import com.arenacity.domain.model.Purchase
import com.arenacity.domain.model.User
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.repository.OpsRepository
import com.arenacity.domain.repository.UserRepository
import com.arenacity.domain.usecase.account.InboxService
import com.arenacity.domain.usecase.analytics.EventLog
import java.time.LocalDate
import java.util.UUID

data class SupportCard(val club: Club, val user: User?, val purchases: List<Purchase>, val lastActive: LocalDate?)

/** Support lookups: find a club by name or id, see its owner, purchases and last activity. */
class SupportQueries(private val ops: OpsRepository, private val clubs: ClubRepository, private val users: UserRepository) {
    suspend fun search(q: String) = ops.searchClubs(q)

    suspend fun card(clubId: UUID): SupportCard {
        val club = clubs.findById(clubId) ?: throw NotFoundException("Club not found")
        val user = users.findById(club.userId)
        return SupportCard(club, user, ops.purchasesOf(club.id), user?.let { ops.lastActive(it.id) })
    }
}

/**
 * Support compensation: coins and/or gold to a human club with a reason. The player sees it in the inbox; the grant is
 * kept in the event log (admin_grant) for audit.
 */
class AdminGrantUseCase(
    private val clubs: ClubRepository,
    private val inbox: InboxService,
    private val events: EventLog,
    private val clock: Clock,
    private val tx: Tx = NoTx,
) {
    suspend operator fun invoke(clubId: UUID, coins: Long, gold: Int, reason: String): Club {
        if (coins < 0 || gold < 0 || (coins == 0L && gold == 0)) throw ValidationException("Grant must be positive")
        if (coins > MAX_COINS || gold > MAX_GOLD) throw ValidationException("Grant too large")
        val why = reason.trim().take(200)
        if (why.isEmpty()) throw ValidationException("Reason required")
        val updated = tx {
            val club = clubs.lockById(clubId)?.takeUnless { it.isBot } ?: throw NotFoundException("Club not found")
            clubs.update(club.copy(coins = club.coins + coins, gold = club.gold + gold, updatedAt = clock.now()))
        }
        inbox.add(updated.id, "GIFT", mapOf("coins" to coins.toString(), "gold" to gold.toString(), "reason" to why))
        events.track(updated.userId, "admin_grant", mapOf("coins" to coins.toString(), "gold" to gold.toString(), "reason" to why))
        return updated
    }

    companion object { const val MAX_COINS = 1_000_000L; const val MAX_GOLD = 10_000 }
}
