package com.matchdayclub.domain.usecase

import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.rules.Cups
import com.matchdayclub.domain.usecase.account.InboxService
import com.matchdayclub.domain.usecase.athlete.SectionService
import com.matchdayclub.domain.usecase.athlete.StarterSquadInitializer
import com.matchdayclub.domain.usecase.club.ClubService
import com.matchdayclub.domain.usecase.club.CreateClubUseCase
import com.matchdayclub.domain.usecase.cup.CupNotifier
import com.matchdayclub.domain.usecase.cup.CupQueries
import com.matchdayclub.domain.usecase.cup.CupService
import com.matchdayclub.domain.usecase.league.*
import com.matchdayclub.presentation.dto.toDto
import com.matchdayclub.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*

class CupTest {
    // Thursday 2026-10-08, 10:00 UTC: the draw is at 12:00, the round of 16 at 18:00 (bucket 0)
    private val clock = FixedClock(Instant.parse("2026-10-08T10:00:00Z"))
    private val users = FakeUserRepository(); private val clubs = FakeClubRepository(); private val buildings = FakeBuildingRepository()
    private val athletes = FakeAthleteRepository(); private val lineups = FakeLineupRepository(); private val leagues = FakeLeagueRepository(); private val fixtures = FakeFixtureRepository()
    private val seasons = FakeSeasonRepository(); private val cupRepo = FakeCupRepository(); private val inboxRepo = FakeInboxRepository()
    private val bots = BotService(users, clubs, athletes, clock, leagues)
    private val leagueService = LeagueService(leagues, fixtures, clubs, bots, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock), JoinPublicLeagueInitializer(clubs, leagueService, clock)))
    private val runner = MatchdayRunner(leagues, fixtures, clubs, athletes, LineupService(athletes, lineups), leagueService, clock, sections = SectionService(buildings, athletes))
    private val cupService = CupService(cupRepo, clubs, fixtures, bots, runner, seasons, clock, notifier = CupNotifier(null, InboxService(inboxRepo, clock)))
    private val queries = CupQueries(ClubService(clubs, buildings, clock), cupRepo, fixtures, clubs, clock)

    private fun newClub(name: String, timezone: String = "UTC", division: Int = 1, rating: Int? = null): Club = runBlocking {
        val user = UUID.randomUUID()
        users.create(User(user, AuthProvider.GUEST, "d-$user", null, null, null, true, clock.now(), clock.now()))
        val c = create(user, name, "#AA0000", "#FFFFFF", timezone)
        if (rating != null) athletes.rows.values.filter { it.clubId == c.id }.forEach { athletes.update(it.copy(attack = rating, defense = rating, physical = rating, technique = rating, mental = rating)) }
        clubs.update(clubs.rows.getValue(c.id).copy(division = division))
    }
    private fun activate(vararg list: Club) { cupRepo.active = cupRepo.active + list.map { it.id to it.tzBucket } }
    private fun at(t: String) { clock.instant = Instant.parse(t) }

    @Test
    fun `cup week schedule in bucket time`() {
        val now = Instant.parse("2026-10-09T10:00:00Z")   // Friday
        assertEquals(LocalDate.parse("2026-10-05"), Cups.weekStart(now, 3))
        assertEquals(Instant.parse("2026-10-08T09:00:00Z"), Cups.drawAt(LocalDate.parse("2026-10-05"), 3))     // Thursday 12:00 +03
        assertEquals(Instant.parse("2026-10-08T15:00:00Z"), Cups.roundAt(LocalDate.parse("2026-10-05"), 1, 3))
        assertEquals(Instant.parse("2026-10-11T15:00:00Z"), Cups.roundAt(LocalDate.parse("2026-10-05"), 4, 3))  // final on Sunday
        assertEquals(Instant.parse("2026-10-15T09:00:00Z"), Cups.nextDraw(now, 3))
        assertEquals(LocalDate.parse("2026-10-12"), Cups.weekStart(Instant.parse("2026-10-11T22:30:00Z"), 3))   // Monday 01:30 at +03
        assertEquals(Cups.Stage.QUARTER_FINAL, Cups.stage(2)); assertEquals(Cups.Stage.CHAMPION, Cups.stage(null))
        assertEquals(5_000, Cups.prize(Cups.Stage.CHAMPION, 1).coins); assertEquals(10_000, Cups.prize(Cups.Stage.CHAMPION, 3).coins)
    }

    @Test
    fun `shootouts always produce a winner and favour the better takers`() {
        var homeWins = 0
        repeat(2_000) { seed ->
            val (h, a) = Cups.shootout(Cups.kickChance(80.0, 40.0), Cups.kickChance(40.0, 80.0), seed.toLong())
            assertNotEquals(h, a); assertTrue(h <= 35 && a <= 35)
            if (h > a) homeWins++
        }
        assertTrue(homeWins in 1_300..1_900, "home won $homeWins of 2000")
        assertEquals(Cups.shootout(0.75, 0.75, 42), Cups.shootout(0.75, 0.75, 42))   // deterministic
    }

    @Test
    fun `active clubs are drawn on Thursday with bots, play four rounds and the winner lifts the trophy`() = runBlocking<Unit> {
        val me = newClub("Lions", rating = 75); val friend = newClub("Wolves"); val third = newClub("Bears")
        val idle = newClub("Sleepers")   // not active this week: not entered
        repeat(2) { bots.createBot(0, 1, "UTC", 5) }   // two free bots with the same name
        activate(me, friend, third)
        assertEquals("UPCOMING", queries.current(me.userId).state)
        assertEquals(Instant.parse("2026-10-08T12:00:00Z"), queries.current(me.userId).drawAt)

        assertEquals(0, cupService.tick(clock.now())); assertTrue(cupRepo.cups.isEmpty())   // before the draw
        at("2026-10-08T12:05:00Z")
        assertEquals(0, cupService.tick(clock.now()))
        val cup = cupRepo.cups.values.single()
        val entrants = cupRepo.entries(cup.id).map { it.clubId }
        assertEquals(16, entrants.size); assertTrue(me.id in entrants && friend.id in entrants && third.id in entrants && idle.id !in entrants)
        assertEquals(16, clubs.findByIds(entrants).map { it.name }.toSet().size, "distinct names in a bracket")
        assertEquals(8, cupRepo.ties(cup.id).size)
        val first = fixtures.rows.values.filter { it.kind == FixtureKind.CUP }
        assertEquals(8, first.size); assertTrue(first.all { it.status == FixtureStatus.SCHEDULED && it.scheduledAt == Instant.parse("2026-10-08T18:00:00Z") && it.leagueId == null })
        assertEquals(3, inboxRepo.rows.count { it.kind == "CUP_DRAW" })
        assertEquals(0, cupService.tick(clock.now())); assertEquals(1, cupRepo.cups.size)   // the draw happens once

        val view = queries.current(me.userId)
        assertEquals("ACTIVE", view.state); assertEquals(8, view.ties.size); assertTrue(view.toDto().me!!.alive)

        at("2026-10-08T18:00:00Z")
        assertEquals(8, cupService.tick(clock.now()))
        assertEquals(1, cupRepo.cups.getValue(cup.id).currentRound)
        assertEquals(8, cupRepo.entries(cup.id).count { it.outRound == 1 })
        assertEquals(4, cupRepo.ties(cup.id).count { it.round == 2 })
        assertEquals(0, cupService.tick(clock.now()))   // round 2 is tomorrow
        for (t in cupRepo.ties(cup.id).filter { it.round == 1 }) {
            val f = fixtures.rows.getValue(t.fixtureId)
            val r = MatchdayResult.parse(f.resultJson!!)
            assertEquals(f.homePoints == f.awayPoints, r.shootout != null, "only level ties go to penalties")
            assertEquals(Cups.winner(f, r), t.winnerClubId)
        }
        val humanLosers = cupRepo.entries(cup.id).filter { it.outRound == 1 && !clubs.rows.getValue(it.clubId).isBot }
        humanLosers.forEach { assertEquals(300, it.prizeCoins) }
        assertTrue(cupRepo.entries(cup.id).filter { clubs.rows.getValue(it.clubId).isBot }.all { it.prizeCoins == 0L })

        // the server was down until Sunday's final: the remaining rounds are caught up in order
        at("2026-10-11T18:00:00Z")
        assertEquals(4 + 2 + 1, cupService.tick(clock.now()))
        val done = cupRepo.cups.getValue(cup.id)
        assertEquals(CupStatus.FINISHED, done.status); assertEquals(4, done.currentRound)
        assertEquals(15, fixtures.rows.values.count { it.kind == FixtureKind.CUP && it.status == FixtureStatus.PLAYED })
        val champion = done.winnerClubId!!
        assertNull(cupRepo.entry(done.weekStart, champion)!!.outRound)
        assertEquals(1, cupRepo.entries(cup.id).count { it.outRound == 4 })
        if (!clubs.rows.getValue(champion).isBot) assertEquals(TrophyKind.CUP, seasons.trophies.single { it.clubId == champion }.kind)
        else assertTrue(seasons.trophies.isEmpty())
        val finalView = queries.current(me.userId).toDto()
        assertEquals("FINISHED", finalView.state); assertEquals(15, finalView.ties.size); assertNotNull(finalView.me!!.stage)
        assertEquals(0, cupService.tick(clock.now()))

        // next Monday: no cup yet this week, last week's result is shown
        at("2026-10-12T09:00:00Z")
        val monday = queries.current(me.userId).toDto()
        assertEquals("UPCOMING", monday.state); assertEquals("2026-10-15T12:00:00Z", monday.drawAt)
        assertEquals(finalView.me!!.stage, monday.previous!!.stage)
    }

    /** Stores a 3–0 win for [club] in its tie of [round] before the scheduler gets there (a result it must respect). */
    private fun rigWin(club: Club, round: Int) {
        val t = cupRepo.ties.single { it.round == round && (it.homeClubId == club.id || it.awayClubId == club.id) }
        val f = fixtures.rows.getValue(t.fixtureId)
        val home = f.homeClubId == club.id
        val (h, a) = if (home) 3 to 0 else 0 to 3
        val r = MatchdayResult(2, listOf(SectionResult("FOOTBALL", h, a, if (home) 3 else 0, if (home) 0 else 3)))
        fixtures.rows[f.id] = f.copy(status = FixtureStatus.PLAYED, homeScore = h, awayScore = a, homePoints = if (home) 3 else 0, awayPoints = if (home) 0 else 3, resultJson = r.toJson(), playedAt = clock.now())
    }

    @Test
    fun `a human champion is paid, gets the trophy and the inbox line`() = runBlocking<Unit> {
        val star = newClub("Stars")
        activate(star)
        at("2026-10-08T12:00:00Z"); cupService.tick(clock.now())
        val coins = clubs.rows.getValue(star.id).coins
        for ((round, day) in listOf(1 to "08", 2 to "09", 3 to "10", 4 to "11")) {
            rigWin(star, round)
            at("2026-10-${day}T18:00:00Z")
            assertEquals((8 shr (round - 1)) - 1, cupService.tick(clock.now()), "round $round: the stored result is kept, the other ties are played")
        }
        val cup = cupRepo.cups.values.single()
        assertEquals(star.id, cup.winnerClubId)
        val c = clubs.rows.getValue(star.id)
        assertEquals(coins + 5_000, c.coins); assertEquals(star.gold + 20, c.gold)
        assertEquals(TrophyKind.CUP, seasons.trophies.single().kind)
        val results = inboxRepo.rows.filter { it.kind == "CUP_RESULT" }.map { kotlinx.serialization.json.Json.decodeFromString<Map<String, String>>(it.dataJson)["verdict"] }
        assertEquals(listOf("THROUGH", "THROUGH", "THROUGH", "CHAMPION"), results)
    }

    @Test
    fun `entrants are grouped by strength into cups of sixteen per bucket`() = runBlocking<Unit> {
        val strong = (1..18).map { newClub("Club $it", division = if (it <= 4) 3 else 1) }
        val kyiv = newClub("Kyiv FC", timezone = "Europe/Kyiv")   // UTC+3 in October: its own bucket
        activate(*strong.toTypedArray(), kyiv)
        at("2026-10-08T12:00:00Z")
        cupService.tick(clock.now())
        val utc = cupRepo.cupsOf(0, LocalDate.parse("2026-10-05"))
        assertEquals(2, utc.size)
        assertEquals(1, cupRepo.cupsOf(3, LocalDate.parse("2026-10-05")).size)
        val tier1 = cupRepo.entries(utc[0].id).map { it.clubId }.toSet()
        assertTrue(strong.take(4).all { it.id in tier1 }, "division-3 clubs are in the first cup")
        val humansSecond = cupRepo.entries(utc[1].id).count { !clubs.rows.getValue(it.clubId).isBot }
        assertEquals(2, humansSecond)   // 18 humans: 16 + 2, bots fill the rest
        assertEquals(1, utc[1].division)
    }

    @Test
    fun `push texts`() {
        val n = CupNotifier()
        val now = clock.now()
        val home = Club(UUID.randomUUID(), UUID.randomUUID(), "Lions", "#000000", "#FFFFFF", null, 1, 0, 0, 0, "UTC", null, null, now, now)
        val away = home.copy(id = UUID.randomUUID(), name = "Wolves")
        val f = Fixture(UUID.randomUUID(), null, FixtureKind.CUP, 0, 2, now, home.id, away.id, FixtureStatus.PLAYED, 1, 1, 1L, null, now, now, 1, 1)
        val d = com.matchdayclub.domain.usecase.cup.DecidedTie(CupTie(UUID.randomUUID(), 2, 0, home.id, away.id, f.id, away.id), f,
            MatchdayResult(2, listOf(SectionResult("FOOTBALL", 1, 1, 1, 1)), Shootout(3, 4)))
        val clubs = mapOf(home.id to home, away.id to away)
        assertEquals("Cup: knocked out" to "Lions 1–1 Wolves, penalties 3–4. Prize: 600 coins.", n.message(d, home.id, 2, clubs, Cups.prize(Cups.Stage.QUARTER_FINAL, 1), "en"))
        assertEquals("Кубок: вы в полуфинале!" to "Wolves 1–1 Lions, пенальти 4–3. Следующий раунд завтра в 18:00.", n.message(d, away.id, 2, clubs, null, "ru"))
    }
}
