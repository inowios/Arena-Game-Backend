package com.arenacity.domain.usecase

import com.arenacity.domain.usecase.alliance.AllianceUseCases
import com.arenacity.domain.usecase.club.*
import com.arenacity.presentation.dto.toDto
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class PresenceTest {
    private val clock = FixedClock(Instant.parse("2026-10-14T12:00:00Z"))
    private val clubs = FakeClubRepository()
    private val spots = FakeClubSpotRepository()
    private val service = ClubService(clubs, clock, spots)
    private val create = CreateClubUseCase(clubs, clock, emptyList())
    private val alliances = AllianceUseCases(service, clubs, FakeAllianceRepository(), clock)
    private val presence = PresenceTracker(clubs, clock)

    private fun club(u: UUID) = clubs.rows.values.first { it.userId == u }

    @Test
    fun `the last-seen time is written at most once a minute per user`() = runBlocking<Unit> {
        val u = UUID.randomUUID()
        assertFalse(presence.seen(u))                      // signed in, no club yet: nothing written, not remembered
        create(u, "Seen FC", "#AA0000", "#FFFFFF", "UTC")
        assertNull(club(u).lastSeenAt)
        assertTrue(presence.seen(u))
        assertEquals(clock.now(), club(u).lastSeenAt)
        clock.advanceSeconds(30); repeat(5) { assertFalse(presence.seen(u)) }
        assertEquals(2, clubs.touches)
        clock.advanceSeconds(31)
        assertTrue(presence.seen(u))
        assertEquals(3, clubs.touches); assertEquals(clock.now(), club(u).lastSeenAt)
        // another user has his own minute
        val v = UUID.randomUUID(); create(v, "Other FC", "#00AA00", "#FFFFFF", "UTC")
        assertTrue(presence.seen(v)); assertEquals(4, clubs.touches)
        // a full club update (a stale copy) never moves the time back
        clubs.update(club(u).copy(coins = 1, lastSeenAt = null))
        assertEquals(clock.now(), club(u).lastSeenAt)
    }

    @Test
    fun `online means seen within the last five minutes`() {
        val now = clock.now()
        assertFalse(Presence.online(null, now))
        assertTrue(Presence.online(now, now))
        assertTrue(Presence.online(now.minusSeconds(Presence.ONLINE_SECONDS), now))
        assertFalse(Presence.online(now.minusSeconds(Presence.ONLINE_SECONDS + 1), now))
    }

    @Test
    fun `the members list says who is online and when the others were last seen`() = runBlocking<Unit> {
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val c = UUID.randomUUID()
        for ((u, n) in listOf(a to "Alpha_FC", b to "Beta_FC", c to "Gamma_FC")) { create(u, n, "#AA0000", "#FFFFFF", "UTC"); clubs.update(club(u).copy(coins = 50_000)) }
        spots.stadium(club(a).id, 2)
        val id = alliances.create(a, "Night Owls", "NO", "", true, 0, null).alliance.id
        alliances.join(b, id); alliances.join(c, id)
        presence.seen(b)                                   // b: seen 10 minutes before the read
        clock.advanceSeconds(600)
        presence.seen(a)                                   // a: just now; c: never
        val members = alliances.mine(a)!!.toDto().members.associateBy { it.club.name }
        assertTrue(members.getValue("Alpha_FC").online)
        assertEquals(clock.now().toString(), members.getValue("Alpha_FC").lastSeenAt)
        assertFalse(members.getValue("Beta_FC").online)
        assertEquals(clock.now().minusSeconds(600).toString(), members.getValue("Beta_FC").lastSeenAt)
        assertFalse(members.getValue("Gamma_FC").online)
        assertNull(members.getValue("Gamma_FC").lastSeenAt)
    }
}
