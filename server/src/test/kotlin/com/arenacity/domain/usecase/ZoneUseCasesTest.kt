package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.InsufficientFundsException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.BuildingState
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.util.UUID


class ZoneUseCasesTest {
    private val clock = FixedClock()
    private val clubs = FakeClubRepository()
    private val buildings = FakeBuildingRepository()
    private val service = ClubService(clubs, buildings, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, emptyList())
    private val get = GetClubUseCase(service)
    private val upgrade = UpgradeBuildingUseCase(service, buildings, clock)
    private val zoneRows = mutableMapOf<Pair<UUID, com.arenacity.domain.model.ZoneSport>, com.arenacity.domain.model.ClubZone>()
    private val zoneRepo = object : com.arenacity.domain.repository.ZoneRepository {
        override suspend fun listByClub(clubId: UUID) = zoneRows.values.filter { it.clubId == clubId }
        override suspend fun upsert(zone: com.arenacity.domain.model.ClubZone) = zone.also { zoneRows[zone.clubId to zone.sport] = it }
    }
    private val zones = ZoneUseCases(service, clubs, zoneRepo, clock)
    private val user = UUID.randomUUID()

    @Test
    fun `a zone needs the stadium level, costs coins, restores on a timer and then counts as the venue`() = runBlocking<Unit> {
        create(user, "Red Lions", "#AA0000", "#FFFFFF", "Europe/Kyiv")
        val all = zones.list(user)
        assertEquals(5, all.size); assertTrue(all.all { it.state == com.arenacity.domain.model.ZoneState.LOCKED })
        assertFailsWith<ValidationException> { zones.buy(user, com.arenacity.domain.model.ZoneSport.BASKETBALL) }   // stadium 1 < 3
        clubs.update(clubs.rows.values.first().copy(coins = 1_000_000, trophies = 100_000))
        repeat(2) { val pitch = get(user).buildings.first { it.type == BuildingType.PITCH }; upgrade(user, pitch.id); clock.advanceSeconds(24 * 3600) }
        assertEquals(3, get(user).stadium)
        val before = get(user).club.coins
        val z = zones.buy(user, com.arenacity.domain.model.ZoneSport.BASKETBALL)
        assertEquals(com.arenacity.domain.model.ZoneState.RESTORING, z.state); assertEquals(2 * 3600L, z.secondsLeft)
        assertEquals(before - z.spec.priceCoins, get(user).club.coins)
        assertFailsWith<ConflictException> { zones.buy(user, com.arenacity.domain.model.ZoneSport.BASKETBALL) }
        assertTrue(zones.openSports(get(user).club.id).isEmpty())
        clock.advanceSeconds(2 * 3600)
        assertEquals(com.arenacity.domain.model.ZoneState.OPEN, zones.list(user).first { it.spec.sport == com.arenacity.domain.model.ZoneSport.BASKETBALL }.state)
        assertEquals(setOf(com.arenacity.domain.model.Sport.BASKETBALL), zones.openSports(get(user).club.id))
        assertFailsWith<ValidationException> { zones.buy(user, com.arenacity.domain.model.ZoneSport.TENNIS) }   // stadium 3 < 4
        Unit
    }
}
