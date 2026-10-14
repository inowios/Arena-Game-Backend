package com.arenacity.domain.usecase.economy

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.Club
import com.arenacity.domain.model.ClubItem
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.repository.InventoryRepository
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.ClubService
import java.util.UUID

/** Ownership of cosmetics: defaults are owned by everyone, the rest come from the pass, achievements, the roads, events and mail. */
class CosmeticsService(private val inventory: InventoryRepository, private val clock: Clock) {
    suspend fun owned(clubId: UUID): Set<String> = Cosmetics.defaults().map { it.id }.toSet() + inventory.items(clubId).map { it.itemId }

    /** Grants [item]; returns false when the club already owned it (the caller pays [Cosmetics.DUPLICATE_GOLD] instead). */
    suspend fun grant(clubId: UUID, item: Cosmetic, source: CosmeticSource): Boolean =
        !Cosmetics.isDefault(item.id) && inventory.add(ClubItem(clubId, item.id, source.name, clock.now()))

    suspend fun requireOwned(clubId: UUID, itemId: String, kind: CosmeticKind): Cosmetic {
        val item = Cosmetics.find(itemId) ?: throw NotFoundException("Unknown item")
        if (item.kind != kind) throw ValidationException("This item doesn't fit here")
        if (itemId !in owned(clubId)) throw ConflictException("You don't own this item yet")
        return item
    }
}

/** Coins for gold (spec §6). */
class BuyCoinPackUseCase(private val service: ClubService, private val clubs: ClubRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, packId: String): Club = tx {
        val pack = Economy.COIN_PACKS[packId] ?: throw NotFoundException("Unknown coin pack")
        val charged = service.charge(service.lockedClubOf(userId), gold = pack.gold)
        clubs.update(charged.copy(coins = charged.coins + pack.coins, updatedAt = clock.now()))
    }
}
