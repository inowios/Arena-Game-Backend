package com.arenacity.domain.usecase

import com.arenacity.domain.model.ClubHero
import com.arenacity.domain.model.FixtureStatus
import com.arenacity.domain.model.HeroLineup
import com.arenacity.domain.model.HeroSource
import com.arenacity.domain.model.LeagueKind
import com.arenacity.domain.model.MatchdayResult
import com.arenacity.domain.rules.HeroSquad
import com.arenacity.domain.rules.Heroes
import com.arenacity.domain.usecase.club.CreateClubUseCase
import com.arenacity.domain.usecase.league.*
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class MatchdayRunnerTest {
    private val clock = FixedClock(Instant.parse("2026-10-09T10:00:00Z"))   // 13:00 Kyiv
    private val users = FakeUserRepository(); private val clubs = FakeClubRepository()
    private val leagues = FakeLeagueRepository(); private val fixtures = FakeFixtureRepository(); private val heroes = FakeHeroRepository()
    private val bots = BotService(users, clubs, clock)
    private val leagueService = LeagueService(leagues, fixtures, clubs, bots, clock)
    private val create = CreateClubUseCase(clubs, clock, listOf(JoinPublicLeagueInitializer(clubs, leagueService, clock)))
    private val runner = MatchdayRunner(leagues, fixtures, clubs, leagueService, clock, heroes = heroes)
    private val user = UUID.randomUUID()

    @Test
    fun `club joins a public league in its timezone bucket and the first round plays at 20 local`() = runBlocking<Unit> {
        val club = create(user, "Lions", "#AA0000", "#FFFFFF", "Europe/Kyiv")
        val league = leagues.listForClub(club.id).single()
        assertEquals(LeagueKind.PUBLIC, league.kind); assertEquals(3, league.tzBucket); assertEquals("2026-10-09", league.seasonStart.toString())
        assertEquals(8, leagues.members(league.id).size); assertEquals(28 * 4, fixtures.rows.size)   // bots + fixtures from day one
        assertEquals(0, runner.runDueRounds(Instant.parse("2026-10-09T16:59:00Z")))
        val played = runner.runDueRounds(Instant.parse("2026-10-09T17:00:00Z"))
        assertEquals(4, played); assertEquals(8, leagues.members(league.id).size); assertEquals(7, clubs.rows.values.count { it.isBot })
        assertEquals(28 * 4, fixtures.rows.size); assertEquals(1, leagues.rows.getValue(league.id).currentRound)
        val mine = fixtures.rows.values.single { it.involves(club.id) && it.round == 1 }
        assertEquals(FixtureStatus.PLAYED, mine.status); assertNotNull(mine.resultJson)
        // an Arena match: 1:0 to the winner, 3 points, two triples, a log
        val r = MatchdayResult.parse(mine.resultJson!!)
        assertEquals(3, r.v); assertEquals(if (r.homeWon) 1 else 0, mine.homeScore); assertEquals(if (r.homeWon) 0 else 1, mine.awayScore)
        assertEquals(if (r.homeWon) 3 else 0, mine.homePoints); assertEquals(if (r.homeWon) 0 else 3, mine.awayPoints)
        assertEquals(3, r.home.size); assertEquals(3, r.away.size); assertTrue(r.turns in 1..40); assertTrue(r.events.any { it.kind == "cast" } && r.events.last().kind == "end", r.events.toString())
        assertTrue(fixtures.rows.values.filter { it.round == 1 }.all { it.homeScore!! + it.awayScore!! == 1 }, "no draws")
        val c = clubs.rows.getValue(club.id)
        assertTrue(c.reputation >= 1 && c.coins > 2500, "rewards applied: rep=${c.reputation} coins=${c.coins}")
        assertEquals(0, runner.runDueRounds(Instant.parse("2026-10-09T17:30:00Z")))   // idempotent
    }

    @Test
    fun `second club in the same bucket shares the league, different bucket gets its own`() = runBlocking<Unit> {
        val a = create(UUID.randomUUID(), "Alpha", "#000000", "#FFFFFF", "Europe/Kyiv")
        val b = create(UUID.randomUUID(), "Bravo", "#000000", "#FFFFFF", "Europe/Warsaw")   // UTC+2 → different bucket
        val c = create(UUID.randomUUID(), "Charlie", "#000000", "#FFFFFF", "Europe/Kyiv")
        assertEquals(leagues.listForClub(a.id).single().id, leagues.listForClub(c.id).single().id)
        assertNotEquals(leagues.listForClub(a.id).single().id, leagues.listForClub(b.id).single().id)
        val shared = leagues.listForClub(a.id).single()
        assertEquals(8, leagues.members(shared.id).size); assertEquals(6, clubs.findByIds(leagues.members(shared.id)).count { it.isBot })   // c replaced a bot
        assertEquals(112, fixtures.rows.values.count { it.leagueId == shared.id })
    }

    @Test
    fun `season rolls over after 28 rounds`() = runBlocking<Unit> {
        val club = create(user, "Lions", "#AA0000", "#FFFFFF", "UTC")
        var t = Instant.parse("2026-10-09T20:00:00Z")
        var total = 0
        repeat(28) { total += runner.runDueRounds(t); t = t.plusSeconds(86_400) }
        assertEquals(112, total)
        val league = leagues.listForClub(club.id).single()
        assertEquals(2, league.seasonNumber); assertEquals(0, league.currentRound)   // rolled over right after round 28
        assertEquals("2026-11-06", league.seasonStart.toString())                      // day after the last matchday
        assertEquals(112, fixtures.rows.size)
        assertEquals(4, runner.runDueRounds(t))                                          // next day 20:00 → season 2 round 1
        assertEquals(112 + 112, fixtures.rows.size)
        assertEquals(1, leagues.rows.getValue(league.id).currentRound)
    }

    @Test
    fun `a human club fields its saved triple, the bots a seeded one, and the heroes who played earn experience`() = runBlocking<Unit> {
        val club = create(user, "Lions", "#AA0000", "#FFFFFF", "Europe/Kyiv")
        for (id in Heroes.START) heroes.insertIgnore(ClubHero(club.id, id, 1, 0, HeroSource.START, clock.now()))
        heroes.update(heroes.listByClub(club.id).first { it.heroId == "p007" }.copy(level = 4))
        heroes.saveLineup(HeroLineup(club.id, listOf("p004", "p001", "p011"), clock.now()))
        assertEquals(4, runner.runDueRounds(Instant.parse("2026-10-09T17:00:00Z")))
        val mine = fixtures.rows.values.single { it.involves(club.id) && it.round == 1 }
        val r = MatchdayResult.parse(mine.resultJson!!)
        val home = mine.homeClubId == club.id
        val ours = if (home) r.home else r.away; val theirs = if (home) r.away else r.home
        assertEquals(listOf("p004", "p001", "p011"), ours.map { it.id }, "the saved triple in its order")
        assertEquals(listOf(1, 1, 1), ours.map { it.level })
        assertEquals(3, theirs.size); assertEquals(3, theirs.map { it.id }.toSet().size); assertTrue(theirs.all { it.id in HeroSquad.HUMANS && it.level == 1 }, "a bot's seeded triple: $theirs")
        // experience as for an Arena match: the three who played, nobody else
        val won = home == r.homeWon
        val xp = heroes.listByClub(club.id).associate { it.heroId to it.xp }
        for (id in listOf("p004", "p001", "p011")) assertEquals(Heroes.matchXp(won, false), xp.getValue(id), "$id earned")
        for (id in listOf("p003", "p007", "p009")) assertEquals(0, xp.getValue(id), "$id sat out")
        assertTrue(heroes.rows.keys.none { clubs.rows.getValue(it.first).isBot }, "bots own no heroes")
        // a short lineup is filled with the best other heroes: the level-4 Ganji first
        heroes.saveLineup(HeroLineup(club.id, listOf("p011"), clock.now()))
        val next = fixtures.rows.values.single { it.involves(club.id) && it.round == 2 }
        assertEquals(listOf("p011", "p007", "p001"), runner.tripleOf(clubs.rows.getValue(club.id), next).map { it.id })
    }

    @Test
    fun `the match is the engine's - seeded, with a log that names the heroes and the skills`() {
        val a = HeroSquad.seeded(1); val b = HeroSquad.seeded(2)
        val r1 = HeroMatch.play(a, b, 42); val r2 = HeroMatch.play(a, b, 42); val r3 = HeroMatch.play(a, b, 43)
        assertEquals(r1.toJson(), r2.toJson()); assertNotEquals(r1.toJson(), r3.toJson())
        assertEquals("HOME", r1.first); assertEquals("AWAY", HeroMatch.play(a, b, 43).first)   // the seed's parity
        val cast = r1.events.first { it.kind == "cast" }
        assertTrue(cast.hero in a.map { it.id } + b.map { it.id }); assertNotNull(cast.skill); assertTrue(cast.skillId!!.startsWith(cast.hero!!))
        assertTrue(r1.events.filter { it.kind == "cast" }.sumOf { it.damage } > 0, "the casts carry their damage")
        val loser = if (r1.homeWon) r1.away else r1.home
        assertTrue(loser.any { it.stamina == 0 } || r1.turns >= 40, "the loser lost heroes or the turns ran out")
        assertEquals(if (r1.homeWon) loser.count { it.stamina == 0 } else 0, r1.homeKnockouts)
        // the fallback triples
        assertEquals(3, HeroSquad.fill(listOf(a[0]), 7).size); assertEquals(a[0].id, HeroSquad.fill(listOf(a[0]), 7)[0].id)
        assertEquals(3, HeroSquad.fill(listOf(a[0]), 7).map { it.id }.toSet().size)
    }

    @Test
    fun `old results (sections, bare football) still read as a result without heroes`() {
        val v2 = """{"v":2,"sections":[{"sport":"FOOTBALL","homeScore":2,"awayScore":1,"homePoints":3,"awayPoints":0}]}"""
        val r = MatchdayResult.parse(v2)
        assertTrue(r.homeWon); assertEquals(0, r.v); assertTrue(r.home.isEmpty() && r.events.isEmpty())
        val v1 = """{"sport":"football","seed":1,"homeTeam":"h","awayTeam":"a","score":{"home":0,"away":2},"events":[],"homePossession":50,"homeShots":5,"awayShots":5}"""
        assertFalse(MatchdayResult.parse(v1).homeWon)
        val v3 = MatchdayResult(homeWon = false, turns = 9, first = "AWAY")
        assertEquals(v3, MatchdayResult.parse(v3.toJson()))
    }
}
