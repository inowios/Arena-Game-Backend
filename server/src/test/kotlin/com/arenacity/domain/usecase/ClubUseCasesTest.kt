package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.InsufficientFundsException
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.ClubZone
import com.arenacity.domain.model.People
import com.arenacity.domain.model.ZoneState
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.presentation.dto.toDto
import com.arenacity.presentation.dto.toLandDto
import com.arenacity.presentation.dto.toResponse
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.util.UUID

class ClubUseCasesTest {
    private val clock = FixedClock()
    private val clubs = FakeClubRepository()
    private val spots = FakeClubSpotRepository()
    private val zones = FakeZoneRepository()
    private val service = ClubService(clubs, clock, spots, zones)
    private val create = CreateClubUseCase(clubs, clock, emptyList())
    private val get = GetClubUseCase(service)
    private val unlock = UnlockSpotUseCase(service, spots, clock)
    private val update = UpdateClubUseCase(service, clubs, clock)
    private val user = UUID.randomUUID()

    private fun newClub() = runBlocking { create(user, "Red Lions", "#AA0000", "#FFFFFF", "Europe/Kyiv") }
    private fun coins(n: Long) = runBlocking { clubs.update(clubs.rows.values.first().copy(coins = n)) }

    @Test
    fun `update club validates and keeps unspecified fields`() = runBlocking<Unit> {
        newClub()
        val c = update(user, null, "#112233", null, """{"shape":"shield","icon":"ball"}""")
        assertEquals("Red Lions", c.name); assertEquals("#112233", c.primaryColor); assertEquals("#FFFFFF", c.secondaryColor); assertTrue(c.emblemJson!!.contains("shield"))
        assertFailsWith<ValidationException> { update(user, "ab", null, null, null) }
        assertFailsWith<ValidationException> { update(user, null, "red", null, null) }
        Unit
    }

    @Test
    fun `a new club has the stadium open and nothing else`() = runBlocking<Unit> {
        val c = newClub()
        val st = get(user)
        assertEquals(2_500, c.coins)
        assertEquals(listOf(MapSpots.STADIUM), st.spots.open); assertEquals(1, st.stadium)
        assertTrue(st.isOpen(MapSpots.STADIUM)); assertFalse(st.isOpen(MapSpots.ACADEMY))
        val land = st.spots.toLandDto()
        assertEquals(listOf("main"), land.regions); assertNull(land.maskId)
        assertEquals(listOf("beast", "stone", "android", "shadow"), land.locked.map { it.id }.sortedBy { listOf("beast", "stone", "android", "shadow").indexOf(it) })
        assertTrue(land.locked.all { it.state == "LOCKED" })
        assertFailsWith<ConflictException> { create(user, "Again", "#000000", "#FFFFFF", "UTC") }
        assertFailsWith<ValidationException> { create(UUID.randomUUID(), "ab", "#000000", "#FFFFFF", "UTC") }
        Unit
    }

    @Test
    fun `the map has ten spots, five bought on the main island and one on each people's island`() {
        assertEquals(listOf("stadium", "academy", "hospital", "mall", "lighthouse", "marina", "android_stadium", "stone_arena", "beast_dome", "shadow_ruins"), MapSpots.ALL.map { it.id })
        assertEquals(mapOf("academy" to 3_000L, "hospital" to 6_000L, "mall" to 12_000L, "lighthouse" to 25_000L, "marina" to 50_000L), MapSpots.BUYABLE.associate { it.id to it.cost })
        assertEquals(MapSpots.MAX_STADIUM, MapSpots.ALL.size)
        val beast = MapSpots.islandSpot(People.BEAST)
        assertEquals("beast_dome", beast.id); assertEquals("beast", beast.island); assertEquals(Zones.spec(People.BEAST).priceCoins, beast.cost)
        assertEquals("BEAST", beast.toDto().people); assertNull(MapSpots.find("mall")!!.toDto().people)
        assertEquals(MapSpots.find("ACADEMY"), MapSpots.find("academy"))
        // the old buildings' tables, by the stadium level now
        assertEquals(listOf(60, 99), listOf(MapSpots.attributeCap(1), MapSpots.attributeCap(10)))
        assertEquals(listOf(500, 26_000), listOf(MapSpots.seats(1), MapSpots.seats(10)))
        assertEquals(listOf(1, 5), listOf(MapSpots.trainingSlots(1), MapSpots.trainingSlots(10)))   // the hero training slots (rules.Heroes.trainingSlots)
    }

    @Test
    fun `unlocking a spot charges its coins, opens it at once and lifts the stadium`() = runBlocking<Unit> {
        newClub(); coins(10_000)
        val st = unlock(user, "academy")
        assertEquals(7_000, st.club.coins); assertEquals(7_000, clubs.rows.values.first().coins)
        assertEquals(listOf("stadium", "academy"), st.spots.open); assertEquals(2, st.stadium)
        // any order: the hospital before the mall
        assertEquals(3, unlock(user, MapSpots.HOSPITAL).stadium)
        assertEquals(1_000, clubs.rows.values.first().coins)
        // the response is GET /club's shape
        val body = get(user).toResponse(listOf("FOOTBALL"), 0)
        assertEquals(3, body.stadium); assertEquals(listOf("stadium", "academy", "hospital"), body.spots)
        assertEquals(listOf("main"), body.land!!.regions)
    }

    @Test
    fun `an open spot, an unknown one or too few coins do not unlock`() = runBlocking<Unit> {
        newClub(); coins(10_000)
        unlock(user, "academy")
        assertEquals("The spot is already open", assertFailsWith<ConflictException> { unlock(user, "academy") }.template)
        assertEquals(7_000, clubs.rows.values.first().coins)                                   // charged once
        assertFailsWith<ConflictException> { unlock(user, "stadium") }                       // always open
        assertEquals("Unknown spot", assertFailsWith<NotFoundException> { unlock(user, "castle") }.template)
        assertEquals("This spot opens with its island", assertFailsWith<ValidationException> { unlock(user, "beast_dome") }.template)
        assertEquals("Not enough coins", assertFailsWith<InsufficientFundsException> { unlock(user, "lighthouse") }.template)   // 25 000 > 7 000
        assertFalse(get(user).isOpen(MapSpots.LIGHTHOUSE)); assertEquals(7_000, clubs.rows.values.first().coins)
        Unit
    }

    @Test
    fun `the stadium level is the open spots, an open island's spot among them`() = runBlocking<Unit> {
        val c = newClub(); coins(1_000_000)
        for (s in listOf("academy", "hospital", "mall")) unlock(user, s)
        assertEquals(4, get(user).stadium)
        // an island still opening does not count, an open one does (and its spot is open)
        zones.upsert(ClubZone(c.id, People.BEAST, ZoneState.RESTORING, clock.now(), clock.now().plusSeconds(3600)))
        assertEquals(4, get(user).stadium); assertFalse(get(user).isOpen("beast_dome"))
        assertEquals("RESTORING", get(user).spots.toLandDto().locked.first { it.id == "beast" }.state)
        clock.advanceSeconds(3600)
        assertEquals(5, get(user).stadium); assertTrue(get(user).isOpen("beast_dome"))
        assertEquals(listOf("main", "beast"), get(user).spots.toLandDto().regions)
        assertEquals(listOf("BEAST"), get(user).spots.toLandDto().peoples)
        assertEquals(5, service.stadiumOf(c.id))
        assertFailsWith<ConflictException> { unlock(user, "beast_dome") }                     // open with its island
        Unit
    }
}
