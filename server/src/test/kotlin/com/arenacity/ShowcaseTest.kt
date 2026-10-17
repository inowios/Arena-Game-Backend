package com.arenacity

import com.arenacity.domain.model.People
import com.arenacity.domain.model.ZoneState
import com.arenacity.domain.rules.MapSpots
import com.arenacity.domain.usecase.athlete.SectionService
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.social.FriendsUseCase
import com.arenacity.domain.usecase.social.GetGroundsUseCase
import com.arenacity.domain.usecase.social.ShowcaseService
import com.arenacity.presentation.dto.toDto
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** The two showcase friends on the five islands map: their open spots make stadium 5 and 10. */
class ShowcaseTest {
    private val clock = FixedClock()
    private val users = FakeUserRepository(); private val clubs = FakeClubRepository()
    private val spots = FakeClubSpotRepository(); private val zones = FakeZoneRepository()
    private val service = ClubService(clubs, clock, spots, zones)
    private val showcase = ShowcaseService(users, clubs, spots, zones, clock)
    private val friends = FriendsUseCase(clubs, service)
    private val grounds = GetGroundsUseCase(clubs, service, SectionService(FakeAthleteRepository()))

    @Test
    fun `the showcases are bots outside every league with stadium 5 and 10 by their open spots`() = runBlocking<Unit> {
        showcase.ensure(); showcase.ensure()   // idempotent
        assertEquals(2, clubs.rows.size)
        assertTrue(clubs.rows.values.all { it.isBot && it.tzBucket == ShowcaseService.BUCKET })
        val list = friends()
        assertEquals(listOf(5, 10), list.map { it.stadium }); assertTrue(list.all { it.showcase })
        val (mid, top) = ShowcaseService.DEFS.map { grounds(it.id) }
        assertEquals(listOf("stadium", "academy", "hospital", "mall", "beast_dome"), mid.spots.open)
        assertEquals(setOf(People.BEAST), mid.spots.peoples)
        assertEquals(MapSpots.ALL.map { it.id }, top.spots.open); assertEquals(10, top.spots.stadium)
        val dto = top.toDto()
        assertEquals(10, dto.stadium); assertEquals(listOf("main", "beast", "stone", "android", "shadow"), dto.land!!.regions)
        assertTrue(dto.buildings.isEmpty()); assertTrue(dto.land!!.locked.isEmpty())
        // one zone too many (an older showcase) is locked again
        zones.open(ShowcaseService.DEFS[0].id, People.SHADOW)
        showcase.ensure()
        assertEquals(ZoneState.LOCKED, zones.listByClub(ShowcaseService.DEFS[0].id).first { it.people == People.SHADOW }.state)
        assertEquals(5, friends().first().stadium)
    }
}
