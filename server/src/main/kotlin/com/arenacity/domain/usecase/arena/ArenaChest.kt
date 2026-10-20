package com.arenacity.domain.usecase.arena

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.model.Club
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.rules.Arena
import com.arenacity.domain.rules.Arenas
import com.arenacity.domain.usecase.club.ClubService
import java.time.Instant
import java.util.UUID

/** The weekly arena chest: once an ISO week (club time), coins and gold by the arena the club stands in (its trophies). */
data class ArenaChest(val arena: Arena, val coins: Long, val gold: Int, val available: Boolean, val nextAt: Instant)

class ArenaChestUseCase(private val service: ClubService, private val clubs: ClubRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    fun view(club: Club, now: Instant): ArenaChest {
        val zone = java.time.ZoneId.of(club.timezone); val arena = Arenas.of(club.trophies)
        return ArenaChest(arena, Arenas.chestCoins(arena), Arenas.chestGold(arena), club.arenaChestWeek != Arenas.isoWeek(now, zone), Arenas.nextWeek(now, zone))
    }
    suspend fun view(userId: UUID): ArenaChest = view(service.clubOf(userId), clock.now())
    suspend fun claim(userId: UUID): Pair<ArenaChest, Club> = tx {
        val club = service.lockedClubOf(userId); val now = clock.now()
        val chest = view(club, now)
        if (!chest.available) throw ConflictException("The arena chest opens next week")
        val saved = clubs.update(club.copy(coins = club.coins + chest.coins, gold = club.gold + chest.gold, arenaChestWeek = Arenas.isoWeek(now, java.time.ZoneId.of(club.timezone)), updatedAt = now))
        chest.copy(available = false) to saved
    }
}
