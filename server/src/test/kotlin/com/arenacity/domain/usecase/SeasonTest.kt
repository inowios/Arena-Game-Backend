package com.arenacity.domain.usecase

import com.arenacity.domain.model.*
import com.arenacity.domain.rules.Divisions
import com.arenacity.domain.rules.LeagueRules
import com.arenacity.domain.usecase.club.CreateClubUseCase
import com.arenacity.domain.usecase.economy.EconomyService
import com.arenacity.domain.usecase.economy.SeasonPassUseCases
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.league.*
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class SeasonTest {
    private val clock = FixedClock(Instant.parse("2026-10-09T10:00:00Z"))
    private val users = FakeUserRepository(); private val clubs = FakeClubRepository()
    private val leagues = FakeLeagueRepository(); private val fixtures = FakeFixtureRepository()
    private val seasons = FakeSeasonRepository(); private val economyRepo = FakeEconomyRepository()
    private val bots = BotService(users, clubs, clock, leagues)
    private val leagueService = LeagueService(leagues, fixtures, clubs, bots, clock)
    private val create = CreateClubUseCase(clubs, clock, listOf(JoinPublicLeagueInitializer(clubs, leagueService, clock)))
    private val seasonService = SeasonService(leagues, fixtures, clubs, seasons, leagueService, clock)
    private val runner = MatchdayRunner(leagues, fixtures, clubs, leagueService, clock, seasonCloser = seasonService)

    private fun newClub(name: String, division: Int = 1): Club = runBlocking {
        val user = UUID.randomUUID()
        users.create(User(user, AuthProvider.GUEST, "d-$user", null, null, null, true, clock.now(), clock.now()))
        var c = create(user, name, "#AA0000", "#FFFFFF", "UTC")
        if (division != 1) {   // move to another division before the season starts: leave the entry league, join one of that division
            val l = leagues.listForClub(c.id).single()
            leagues.removeMember(l.id, c.id); fixtures.deleteScheduled(l.id, l.seasonNumber); leagueService.prepareSeason(l)
            c = clubs.update(clubs.rows.getValue(c.id).copy(division = division)); leagueService.ensurePublicMembership(c)
        }
        clubs.rows.getValue(c.id)
    }

    /** Every match of [club] this season stored as a [won] result before the scheduler gets there (an Arena match cannot be rigged by strength: every triple is the original's balance). */
    private fun rig(club: Club, won: Boolean) = runBlocking {
        val league = leagues.listForClub(club.id).single { it.status == LeagueStatus.ACTIVE }
        for (f in fixtures.listByLeagueSeason(league.id, league.seasonNumber).filter { it.involves(club.id) && it.status == FixtureStatus.SCHEDULED }) {
            val r = MatchdayResult(homeWon = (f.homeClubId == club.id) == won)
            fixtures.rows[f.id] = f.copy(status = FixtureStatus.PLAYED, homeScore = r.homeScore, awayScore = r.awayScore, homePoints = r.homePoints, awayPoints = r.awayPoints, resultJson = r.toJson(), playedAt = clock.now())
        }
    }

    private fun playSeason(): Int {
        var t = Instant.parse("2026-10-09T20:00:00Z"); var played = 0
        repeat(LeagueRules.ROUNDS) { played += runBlocking { runner.runDueRounds(t) }; t = t.plusSeconds(86_400) }
        return played
    }

    @Test
    fun `strong club wins the entry division, gets prizes, trophies and moves up`() = runBlocking<Unit> {
        val c = newClub("Champs"); rig(c, won = true)
        val oldLeague = leagues.listForClub(c.id).single()
        assertEquals(LeagueRules.ROUNDS * 3, playSeason())   // our 28 were stored already
        assertEquals(LeagueStatus.FINISHED, leagues.rows.getValue(oldLeague.id).status)
        assertEquals(8, seasons.seasons.count { it.leagueId == oldLeague.id }, "a history row for every member")
        val rec = seasons.seasonFor(c.id, oldLeague.id)!!
        assertEquals(1, rec.position); assertEquals(Movement.PROMOTED, rec.movement); assertEquals(2, rec.newDivision)
        val after = clubs.rows.getValue(c.id)
        assertEquals(2, after.division); assertEquals(2, after.seasonNo)
        assertEquals(Divisions.prize(1, 1).gold + c.gold, after.gold)
        assertEquals(setOf(TrophyKind.LEAGUE_TITLE, TrophyKind.PROMOTION), seasons.trophiesOf(c.id).map { it.kind }.toSet())
        val next = leagues.listForClub(c.id).single { it.status == LeagueStatus.ACTIVE && it.kind == LeagueKind.PUBLIC }
        assertEquals(2, next.division); assertEquals(0, next.currentRound); assertEquals(8, leagues.members(next.id).size)
        assertEquals("2026-11-06", next.seasonStart.toString())
        assertTrue(leagues.members(next.id).filter { it != c.id }.all { clubs.rows.getValue(it).isBot && clubs.rows.getValue(it).division == 2 })
        // the next season is played in the new league
        assertEquals(4, runner.runDueRounds(Instant.parse("2026-11-06T20:00:00Z")))
        assertEquals(1, leagues.rows.getValue(next.id).currentRound)
    }

    @Test
    fun `weak club is relegated from division 2`() = runBlocking<Unit> {
        val c = newClub("Strugglers", division = 2); rig(c, won = false)
        val league = leagues.listForClub(c.id).single { it.status == LeagueStatus.ACTIVE }
        assertEquals(2, league.division)
        playSeason()
        val rec = seasons.seasonFor(c.id, league.id)!!
        assertEquals(8, rec.position); assertEquals(Movement.RELEGATED, rec.movement)
        assertEquals(1, clubs.rows.getValue(c.id).division)
        assertTrue(seasons.trophiesOf(c.id).isEmpty())
    }

    @Test
    fun `bots of a finished season are reused by the next league in that division`() = runBlocking<Unit> {
        newClub("First")
        playSeason()
        val botsBefore = clubs.rows.values.count { it.isBot }
        newClub("Newcomer")   // joins division 1 of the same bucket: its league is filled with freed bots
        assertEquals(botsBefore, clubs.rows.values.count { it.isBot }, "no new bots were created")
    }

    @Test
    fun `season pass follows the club season counter`() = runBlocking<Unit> {
        val c = newClub("Passers")
        val service = ClubService(clubs, clock)
        val economy = EconomyService(economyRepo, leagues, clock)
        val pass = SeasonPassUseCases(service, clubs, economyRepo, economy, clock)
        assertEquals(1, pass.view(c.userId).seasonNumber)
        playSeason()
        assertEquals(2, pass.view(c.userId).seasonNumber)
    }
}

class SeasonPushTextTest {
    @org.junit.jupiter.api.Test
    fun `season push is written in the player's language`() {
        val rec = com.arenacity.domain.model.ClubSeason(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), 2, 1, 1, 28, 20, 4, 4, 60, 20, 64,
            com.arenacity.domain.model.Movement.PROMOTED, 2, 5_000, 20, 50, java.time.Instant.EPOCH)
        val service = com.arenacity.domain.usecase.league.SeasonService(FakeLeagueRepositoryForText, com.arenacity.testutil.FakeFixtureRepository(), com.arenacity.testutil.FakeClubRepository(),
            com.arenacity.testutil.FakeSeasonRepository(), com.arenacity.domain.usecase.league.LeagueService(FakeLeagueRepositoryForText, com.arenacity.testutil.FakeFixtureRepository(), com.arenacity.testutil.FakeClubRepository(),
                com.arenacity.domain.usecase.league.BotService(com.arenacity.testutil.FakeUserRepository(), com.arenacity.testutil.FakeClubRepository(), com.arenacity.testutil.FixedClock()), com.arenacity.testutil.FixedClock()),
            com.arenacity.testutil.FixedClock())
        val (ruTitle, ruBody) = service.message(rec, "ru")
        kotlin.test.assertEquals("Сезон окончен: вы на 1-м месте", ruTitle)
        kotlin.test.assertTrue(ruBody.contains("Районная лига") && ruBody.contains("5000"), ruBody)
        val (enTitle, enBody) = service.message(rec, "en")
        kotlin.test.assertEquals("Season over: you finished #1", enTitle); kotlin.test.assertTrue(enBody.startsWith("Promoted to the County League!"), enBody)
    }
}

private val FakeLeagueRepositoryForText = com.arenacity.testutil.FakeLeagueRepository()
