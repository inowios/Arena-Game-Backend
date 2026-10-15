package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.ClubZone
import com.arenacity.domain.model.People
import com.arenacity.domain.model.ZoneState
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.presentation.dto.toDto
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.util.UUID

/** The bridges to the peoples' islands (the owner, 15 October 2026): rules.Zones, ZoneUseCases. */
class ZoneUseCasesTest {
    private val clock = FixedClock()
    private val clubs = FakeClubRepository()
    private val buildings = FakeBuildingRepository()
    private val zoneRows = mutableMapOf<Pair<UUID, People>, ClubZone>()
    private val zoneRepo = object : com.arenacity.domain.repository.ZoneRepository {
        override suspend fun listByClub(clubId: UUID) = zoneRows.values.filter { it.clubId == clubId }
        override suspend fun upsert(zone: ClubZone) = zone.also { zoneRows[zone.clubId to zone.people] = it }
    }
    private val service = ClubService(clubs, buildings, clock).apply { openZones = { id -> openPeoples(zoneRepo, id, clock.now()) } }
    private val create = CreateClubUseCase(clubs, buildings, clock, emptyList())
    private val get = GetClubUseCase(service)
    private val upgrade = UpgradeBuildingUseCase(service, buildings, clock)
    private val zones = ZoneUseCases(service, clubs, zoneRepo, clock)
    private val user = UUID.randomUUID()

    @Test
    fun `a bridge needs its people's stadium, costs coins, is built on a timer and then the people returns`() = runBlocking<Unit> {
        create(user, "Red Lions", "#AA0000", "#FFFFFF", "Europe/Kyiv")
        val all = zones.list(user)
        assertEquals(People.entries.toSet(), all.map { it.spec.people }.toSet()); assertTrue(all.all { it.state == ZoneState.LOCKED })
        // every island under its dome: locked, with its people and its bridge's stadium
        val land = get(user).land.toDto(service.lands)
        assertEquals(listOf("main"), land.regions); assertTrue(land.peoples.isEmpty())
        assertEquals(mapOf("beast" to ("BEAST" to 4), "stone" to ("STONE" to 5), "android" to ("ANDROID" to 7), "shadow" to ("SHADOW" to 10)),
            land.locked.associate { it.id to (it.people!! to it.requiredStadium) })
        assertEquals(land.locked.map { it.people }, land.locked.map { it.zone })
        assertEquals("Needs stadium level {0}", assertFailsWith<ValidationException> { zones.buy(user, People.BEAST) }.template)   // stadium 1 < 4
        clubs.update(clubs.rows.values.first().copy(coins = 1_000_000, trophies = 100_000))
        repeat(3) { val pitch = get(user).buildings.first { it.type == BuildingType.PITCH }; upgrade(user, pitch.id); clock.advanceSeconds(24 * 3600) }
        assertEquals(4, get(user).stadium)
        val before = get(user).club.coins
        val z = zones.buy(user, People.BEAST)
        assertEquals(ZoneState.RESTORING, z.state); assertEquals(2 * 3600L, z.secondsLeft); assertEquals(8_000L, z.spec.priceCoins)
        assertEquals(before - z.spec.priceCoins, get(user).club.coins)
        assertFailsWith<ConflictException> { zones.buy(user, People.BEAST) }
        assertTrue(openPeoples(zoneRepo, get(user).club.id, clock.now()).isEmpty(), "the bridge is still being built")
        clock.advanceSeconds(2 * 3600)
        assertEquals(ZoneState.OPEN, zones.list(user).first { it.spec.people == People.BEAST }.state)
        assertEquals(setOf(People.BEAST), openPeoples(zoneRepo, get(user).club.id, clock.now()))
        // the island is the club's land now, its dome gone, its people back (and its cards in the packs)
        val open = get(user).land.toDto(service.lands)
        assertEquals(listOf("main", "beast"), open.regions); assertEquals(listOf("BEAST"), open.peoples)
        assertTrue(open.locked.none { it.id == "beast" })
        assertTrue(Peoples.dropsInPacks(People.BEAST, openPeoples(zoneRepo, get(user).club.id, clock.now())))
        assertFalse(Peoples.dropsInPacks(People.STONE, openPeoples(zoneRepo, get(user).club.id, clock.now())))
        assertEquals("Needs stadium level {0}", assertFailsWith<ValidationException> { zones.buy(user, People.ANDROID) }.template)   // stadium 4 < 7
        // the client's dto: the people's key in both fields
        assertEquals("BEAST", zones.list(user).first { it.spec.people == People.BEAST }.toDto().people)
        assertEquals("BEAST", zones.list(user).first { it.spec.people == People.BEAST }.toDto().sport)
    }

    @Test
    fun `an island opened at the sport town's lower stadium stays open, a mask without the island sells no bridge`() = runBlocking<Unit> {
        val club = create(user, "Red Lions", "#AA0000", "#FFFFFF", "Europe/Kyiv")
        clubs.update(clubs.rows.values.first().copy(coins = 10_000_000, trophies = 100_000))
        // a club that opened the basketball island at stadium 3 (V44 made the row BEAST): open at stadium 3 still
        buildings.setStadium(club.id, 3)
        zoneRepo.upsert(ClubZone(club.id, People.BEAST, ZoneState.OPEN, clock.now(), null))
        assertEquals(ZoneState.OPEN, zones.list(user).first { it.spec.people == People.BEAST }.state)
        assertTrue(get(user).land.ids.contains("beast"))
        // stadium 9: the shadows (10) not yet
        buildings.setStadium(club.id, 9)
        assertEquals(10, zones.list(user).first { it.spec.people == People.SHADOW }.requiredStadium)
        assertEquals("Needs stadium level {0}", assertFailsWith<ValidationException> { zones.buy(user, People.SHADOW) }.template)
        buildings.setStadium(club.id, 10)
        assertEquals(ZoneState.RESTORING, zones.buy(user, People.SHADOW).state)
        // a mask with no stone island: its bridge is not sold
        val notched = ClubService(clubs, buildings, clock, lands = TestLand.NOTCHED)
        val other = ZoneUseCases(notched, clubs, zoneRepo, clock)
        assertEquals("This zone has no island yet", assertFailsWith<ValidationException> { other.buy(user, People.STONE) }.template)
    }
}
