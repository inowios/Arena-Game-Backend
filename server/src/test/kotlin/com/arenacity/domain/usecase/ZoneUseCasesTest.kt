package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.ClubZone
import com.arenacity.domain.model.People
import com.arenacity.domain.model.ZoneState
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.presentation.dto.toDto
import com.arenacity.presentation.dto.toLandDto
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.util.UUID

/** The bridges to the peoples' islands (the owner, 15 October 2026): rules.Zones, ZoneUseCases. */
class ZoneUseCasesTest {
    private val clock = FixedClock()
    private val clubs = FakeClubRepository()
    private val zoneRows = mutableMapOf<Pair<UUID, People>, ClubZone>()
    private val zoneRepo = object : com.arenacity.domain.repository.ZoneRepository {
        override suspend fun listByClub(clubId: UUID) = zoneRows.values.filter { it.clubId == clubId }
        override suspend fun upsert(zone: ClubZone) = zone.also { zoneRows[zone.clubId to zone.people] = it }
    }
    private val spots = FakeClubSpotRepository()
    private val service = ClubService(clubs, clock, spots, zoneRepo)
    private val create = CreateClubUseCase(clubs, clock, emptyList())
    private val get = GetClubUseCase(service)
    private val unlock = UnlockSpotUseCase(service, spots, clock)
    private val zones = ZoneUseCases(service, clubs, zoneRepo, clock)
    private val user = UUID.randomUUID()

    @Test
    fun `an island needs its people's stadium, costs coins, opens on a timer and then the people and its spot are back`() = runBlocking<Unit> {
        create(user, "Red Lions", "#AA0000", "#FFFFFF", "Europe/Kyiv")
        val all = zones.list(user)
        assertEquals(People.entries.toSet(), all.map { it.spec.people }.toSet()); assertTrue(all.all { it.state == ZoneState.LOCKED })
        // every island under its dome: locked, with its people and its bridge's stadium
        val land = get(user).spots.toLandDto()
        assertEquals(listOf("main"), land.regions); assertTrue(land.peoples.isEmpty())
        assertEquals(mapOf("beast" to ("BEAST" to 4), "stone" to ("STONE" to 5), "android" to ("ANDROID" to 7), "shadow" to ("SHADOW" to 9)),
            land.locked.associate { it.id to (it.people!! to it.requiredStadium) })
        assertEquals(land.locked.map { it.people }, land.locked.map { it.zone })
        assertEquals("Needs stadium level {0}", assertFailsWith<ValidationException> { zones.buy(user, People.BEAST) }.template)   // stadium 1 < 4
        clubs.update(clubs.rows.values.first().copy(coins = 1_000_000, trophies = 100_000))
        // the stadium and three main-island spots: stadium 4
        for (s in listOf(MapSpots.ACADEMY, MapSpots.HOSPITAL, MapSpots.MALL)) unlock(user, s)
        assertEquals(4, get(user).stadium)
        val before = get(user).club.coins
        val z = zones.buy(user, People.BEAST)
        assertEquals(ZoneState.RESTORING, z.state); assertEquals(2 * 3600L, z.secondsLeft); assertEquals(8_000L, z.spec.priceCoins)
        assertEquals(before - z.spec.priceCoins, get(user).club.coins)
        assertFailsWith<ConflictException> { zones.buy(user, People.BEAST) }
        assertTrue(get(user).spots.peoples.isEmpty(), "the island is still opening")
        assertEquals("RESTORING", get(user).spots.toLandDto().locked.first { it.id == "beast" }.state)
        assertFalse(get(user).isOpen("beast_dome")); assertEquals(4, get(user).stadium)
        clock.advanceSeconds(2 * 3600)
        assertEquals(ZoneState.OPEN, zones.list(user).first { it.spec.people == People.BEAST }.state)
        assertEquals(setOf(People.BEAST), get(user).spots.peoples)
        // its spot is open: one more open spot, stadium 5
        assertTrue(get(user).isOpen("beast_dome")); assertEquals(5, get(user).stadium)
        // the island is the club's land now, its dome gone, its people back
        val open = get(user).spots.toLandDto()
        assertEquals(listOf("main", "beast"), open.regions); assertEquals(listOf("BEAST"), open.peoples)
        assertTrue(open.locked.none { it.id == "beast" })
        assertEquals("Needs stadium level {0}", assertFailsWith<ValidationException> { zones.buy(user, People.ANDROID) }.template)   // stadium 5 < 7
        // the client's dto: the people's key in both fields
        assertEquals("BEAST", zones.list(user).first { it.spec.people == People.BEAST }.toDto().people)
        assertEquals("BEAST", zones.list(user).first { it.spec.people == People.BEAST }.toDto().sport)
    }

    @Test
    fun `an island opened earlier stays open and counts, the shadows open once every other spot is`() = runBlocking<Unit> {
        val club = create(user, "Red Lions", "#AA0000", "#FFFFFF", "Europe/Kyiv")
        clubs.update(clubs.rows.values.first().copy(coins = 10_000_000, trophies = 100_000))
        // a club that opened the basketball island at the sport town (V44 made the row BEAST): open, its spot counts
        zoneRepo.upsert(ClubZone(club.id, People.BEAST, ZoneState.OPEN, clock.now(), null))
        assertEquals(ZoneState.OPEN, zones.list(user).first { it.spec.people == People.BEAST }.state)
        assertTrue(get(user).spots.toLandDto().regions.contains("beast"))
        assertEquals(listOf("stadium", "beast_dome"), get(user).spots.open); assertEquals(2, get(user).stadium)
        // every main spot and the stone island: stadium 8; the androids too: 9, the shadows. island the last one
        for (s in MapSpots.BUYABLE) unlock(user, s.id)
        zoneRepo.upsert(ClubZone(club.id, People.STONE, ZoneState.OPEN, clock.now(), null))
        assertEquals(8, get(user).stadium)
        assertEquals(9, zones.list(user).first { it.spec.people == People.SHADOW }.requiredStadium)
        assertEquals("Needs stadium level {0}", assertFailsWith<ValidationException> { zones.buy(user, People.SHADOW) }.template)
        zoneRepo.upsert(ClubZone(club.id, People.ANDROID, ZoneState.OPEN, clock.now(), null))
        assertEquals(9, get(user).stadium)
        assertEquals(ZoneState.RESTORING, zones.buy(user, People.SHADOW).state)
        clock.advanceSeconds(12 * 3600)
        assertEquals(10, get(user).stadium); assertEquals(MapSpots.ALL.map { it.id }, get(user).spots.open)
        assertTrue(get(user).spots.toLandDto().locked.isEmpty())
    }
}
