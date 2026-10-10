package com.matchdayclub.domain.usecase.economy

import com.matchdayclub.common.time.Clock
import com.matchdayclub.common.tx.NoTx
import com.matchdayclub.common.tx.Tx
import com.matchdayclub.domain.model.ClubItem
import com.matchdayclub.domain.model.SeasonPass
import com.matchdayclub.domain.repository.ClubRepository
import com.matchdayclub.domain.repository.EconomyRepository
import com.matchdayclub.domain.repository.InventoryRepository
import com.matchdayclub.domain.rules.Economy
import com.matchdayclub.domain.usecase.account.InboxService
import com.matchdayclub.domain.usecase.analytics.EventLog
import org.slf4j.LoggerFactory

/** What a store notification asks for. */
enum class RefundAction { REFUND, RESTORE }
enum class RefundOutcome { APPLIED, ALREADY, UNKNOWN }

/**
 * Store refunds and revocations (GAME-PLAN Е3): App Store Server Notifications V2 (REFUND, REVOKE, REFUND_REVERSED)
 * and Google Play voided purchases. A refund takes back what the purchase gave — gold and coins as far as the club
 * still has them (never below zero; what was already spent stays spent), the premium pass and the product's item —
 * marks the purchase REFUNDED and tells the player in the inbox. A reversed refund gives it all back. Idempotent:
 * stores resend notifications. A refunded one-time product (the starter pack) can be bought again.
 */
class RefundUseCase(
    private val clubs: ClubRepository,
    private val repo: EconomyRepository,
    private val inventory: InventoryRepository,
    private val clock: Clock,
    private val tx: Tx = NoTx,
    private val inbox: InboxService? = null,
    private val events: EventLog? = null,
) {
    private val log = LoggerFactory.getLogger("Refunds")

    /** [externalIds]: the purchase's possible keys (e.g. "google:<orderId>" and the token hash); the first known one is used. */
    suspend operator fun invoke(action: RefundAction, externalIds: List<String>): RefundOutcome {
        val purchase = externalIds.firstNotNullOfOrNull { repo.purchaseByExternalId(it) } ?: run {
            log.info("$action for an unknown purchase ${externalIds.firstOrNull()}"); return RefundOutcome.UNKNOWN
        }
        val wanted = if (action == RefundAction.REFUND) "REFUNDED" else "GRANTED"
        if (purchase.status == wanted) return RefundOutcome.ALREADY
        val product = Economy.PRODUCTS[purchase.productId]
        val (gold, coins) = tx {
            val club = clubs.lockById(purchase.clubId) ?: return@tx 0 to 0L
            val now = clock.now()
            val result = if (action == RefundAction.REFUND) {
                val g = minOf(club.gold, purchase.grantedGold).coerceAtLeast(0); val c = minOf(club.coins, purchase.grantedCoins).coerceAtLeast(0)
                clubs.update(club.copy(gold = club.gold - g, coins = club.coins - c, updatedAt = now))
                if (product?.premiumPass == true) repo.pass(club.id, club.seasonNo)?.let { repo.upsertPass(it.copy(premium = false)) }
                product?.item?.let { inventory.remove(club.id, it) }
                g to c
            } else {
                clubs.update(club.copy(gold = club.gold + purchase.grantedGold, coins = club.coins + purchase.grantedCoins, updatedAt = now))
                if (product?.premiumPass == true) (repo.pass(club.id, club.seasonNo) ?: SeasonPass(club.id, club.seasonNo, 0, false, 0, 0)).let { repo.upsertPass(it.copy(premium = true)) }
                product?.item?.let { inventory.add(ClubItem(club.id, it, "SHOP", now)) }
                purchase.grantedGold to purchase.grantedCoins
            }
            repo.setPurchaseStatus(purchase.id, wanted)
            result
        }
        val kind = if (action == RefundAction.REFUND) "REFUND" else "REFUND_REVERSED"
        inbox?.add(purchase.clubId, kind, mapOf("product" to purchase.productId, "gold" to gold.toString(), "coins" to coins.toString()))
        events?.track(null, if (action == RefundAction.REFUND) "purchase_refunded" else "purchase_restored",
            mapOf("product" to purchase.productId, "store" to purchase.store, "gold" to gold.toString(), "short" to (purchase.grantedGold - gold).toString()))
        log.info("$kind ${purchase.externalId} (${purchase.productId}): gold $gold, coins $coins")
        return RefundOutcome.APPLIED
    }
}
