package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.usecase.athlete.CardMatchReport
import com.arenacity.domain.usecase.athlete.CardMatchUseCases
import com.arenacity.domain.usecase.athlete.StarterSquadInitializer
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.club.CreateClubUseCase
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class CardMatchTest {
    private val clock = FixedClock(Instant.parse("2026-10-11T10:00:00Z"))
    private val clubs = FakeClubRepository(); private val buildings = FakeBuildingRepository(); private val athletes = FakeAthleteRepository()
    private val service = ClubService(clubs, buildings, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock)))
    private val use = CardMatchUseCases(service, clubs, athletes, clock)
    private val u1 = UUID.randomUUID()

    @Test
    fun `a reported card match pays coins and gives the cards their experience, within bounds`() = runBlocking<Unit> {
        val club = create(u1, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val squad = athletes.listByClub(club.id)
        val a = squad[0]; val b = squad[1]
        val o = use.report(u1, CardMatchReport(7, won = true, draw = false, goalsFor = 2, goalsAgainst = 1, cards = mapOf(a.id to 15, b.id to 99, UUID.randomUUID() to 10)))
        assertEquals(120, o.coins); assertEquals(club.coins + 120, o.club.coins)
        assertEquals(2, o.athletes.size)
        assertEquals(a.xpTotal + 15, athletes.rows[a.id]!!.xpTotal)
        assertEquals(b.xpTotal + CardMatchUseCases.MAX_XP_PER_CARD, athletes.rows[b.id]!!.xpTotal)
        assertEquals(20, use.report(u1, CardMatchReport(8, won = false, draw = false, goalsFor = 0, goalsAgainst = 3, cards = emptyMap())).coins)
        assertFailsWith<ValidationException> { use.report(u1, CardMatchReport(9, won = true, draw = false, goalsFor = 99, goalsAgainst = 0, cards = emptyMap())) }
    }
}
