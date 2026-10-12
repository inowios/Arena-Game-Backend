package com.arenacity.domain.usecase.economy

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.Building
import com.arenacity.domain.model.Club
import com.arenacity.domain.model.ClubItem
import com.arenacity.domain.repository.BuildingRepository
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.repository.InventoryRepository
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.ClubService
import java.util.UUID

/** Ownership of cosmetics: defaults are owned by everyone, the rest come from the shop, the pass or achievements. */
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

data class Inventory(val owned: Set<String>, val club: Club)

class CosmeticsUseCases(
    private val service: ClubService,
    private val clubs: ClubRepository,
    private val buildings: BuildingRepository,
    private val cosmetics: CosmeticsService,
    private val clock: Clock,
    private val tx: Tx = NoTx,
) {
    suspend fun inventory(userId: UUID): Inventory { val club = service.clubOf(userId); return Inventory(cosmetics.owned(club.id), club) }

    suspend fun buy(userId: UUID, itemId: String): Inventory = tx {
        val item = Cosmetics.find(itemId) ?: throw NotFoundException("Unknown item")
        if (!item.purchasable) throw ValidationException("This item can't be bought, it is earned")
        val club = service.lockedClubOf(userId)
        if (itemId in cosmetics.owned(club.id)) throw ConflictException("Already owned")
        val charged = service.charge(club, gold = item.gold)
        cosmetics.grant(club.id, item, CosmeticSource.SHOP)
        Inventory(cosmetics.owned(club.id), charged)
    }

    /** Equips a kit, emblem frame or celebration; null (or the default item) restores the default look. */
    suspend fun equip(userId: UUID, kind: CosmeticKind, itemId: String?): Club = tx {
        if (kind == CosmeticKind.DECOR) throw ValidationException("Decor looks are chosen per decoration")
        val club = service.lockedClubOf(userId)
        if (itemId != null) cosmetics.requireOwned(club.id, itemId, kind)
        val v = itemId?.takeUnless { Cosmetics.isDefault(it) }
        val updated = when (kind) {
            CosmeticKind.KIT -> club.copy(kit = v)
            CosmeticKind.FRAME -> club.copy(emblemFrame = v)
            CosmeticKind.CELEBRATION -> club.copy(celebration = v)
            CosmeticKind.DECOR -> club
        }
        clubs.update(updated.copy(updatedAt = clock.now()))
    }

    /** Changes the look of one placed decoration. */
    suspend fun setDecorLook(userId: UUID, buildingId: UUID, itemId: String): Building = tx {
        val club = service.lockedClubOf(userId)
        val b = service.building(club, buildingId)
        if (b.type != BuildingType.DECOR) throw ValidationException("Only decorations have looks")
        cosmetics.requireOwned(club.id, itemId, CosmeticKind.DECOR)
        buildings.update(b.copy(variant = itemId.takeUnless { Cosmetics.isDefault(it) }, updatedAt = clock.now()))
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
