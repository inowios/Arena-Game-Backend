package com.matchdayclub.domain.usecase

import com.matchdayclub.domain.exception.*
import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.usecase.athlete.SectionService
import com.matchdayclub.domain.usecase.athlete.StarterSquadInitializer
import com.matchdayclub.domain.usecase.club.ClubService
import com.matchdayclub.domain.usecase.club.CreateClubUseCase
import com.matchdayclub.domain.usecase.league.*
import com.matchdayclub.domain.usecase.social.*
import com.matchdayclub.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class SocialTest {
    private val clock = FixedClock(Instant.parse("2026-10-09T10:00:00Z"))
    private val users = FakeUserRepository(); private val clubs = FakeClubRepository(); private val buildings = FakeBuildingRepository()
    private val athletes = FakeAthleteRepository(); private val lineups = FakeLineupRepository(); private val leagues = FakeLeagueRepository(); private val fixtures = FakeFixtureRepository()
    private val chatRepo = FakeChatRepository(); private val social = FakeSocialRepository()
    private val service = ClubService(clubs, buildings, clock)
    private val bots = BotService(users, clubs, athletes, clock, leagues)
    private val leagueService = LeagueService(leagues, fixtures, clubs, bots, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock), JoinPublicLeagueInitializer(clubs, leagueService, clock)))
    private val queries = LeagueQueries(leagues, fixtures, clubs, athletes, clock)
    private val runner = MatchdayRunner(leagues, fixtures, clubs, athletes, LineupService(athletes, lineups), leagueService, clock, sections = SectionService(buildings, athletes))
    private val chat = ChatService(service, leagues, clubs, chatRepo, clock)
    private val invites = InviteTracker(social, clubs, clock)
    private val createPrivate = CreatePrivateLeagueUseCase(service, leagues, clock)
    private val join = JoinPrivateLeagueUseCase(service, leagues, invites = invites, chat = chat)
    private val leave = LeavePrivateLeagueUseCase(service, leagues, chat = chat)
    private val admin = PrivateLeagueAdmin(service, leagues, clubs, social, queries, chat, clock)
    private val challenges = ChallengeUseCases(service, clubs, fixtures, social, runner, queries, clock)
    private val h2h = HeadToHeadUseCase(service, clubs, fixtures)

    private fun user(name: String): Pair<UUID, Club> = runBlocking {
        val u = UUID.randomUUID(); users.create(User(u, AuthProvider.GUEST, "d-$u", null, null, null, true, clock.now(), clock.now()))
        u to create(u, name, "#AA0000", "#FFFFFF", "UTC")
    }

    @Test
    fun `chat is members-only, masked, rate limited and moderated`() = runBlocking {
        val (u1, a) = user("Alpha"); val (u2, b) = user("Bravo"); val (u3, _) = user("Charlie")
        val league = createPrivate(u1, "Office")
        join(u2, league.code!!)
        clock.advanceSeconds(1)
        assertFailsWith<AuthorizationException> { chat.post(u3, league.id, "hi") }
        val first = chat.post(u1, league.id, "hello   fucking   world")
        assertEquals("hello ******* world", first.message.text)
        assertFailsWith<ConflictException> { chat.post(u1, league.id, "again") }              // 2 s between lines
        clock.advanceSeconds(3)
        chat.post(u1, league.id, "ok now")
        val lines = chat.list(u2, league.id, null, null)
        assertEquals(listOf("SYSTEM", "USER", "USER"), lines.map { it.message.kind.name })   // "Bravo joined" + two lines
        assertTrue(lines.first().message.text.startsWith("join|Bravo"))
        chat.block(u2, a.id)
        assertEquals(1, chat.list(u2, league.id, null, null).size, "blocked author hidden for the blocker only")
        assertEquals(3, chat.list(u1, league.id, null, null).size)
        val (u4, _) = user("Delta"); val (u5, _) = user("Echo"); join(u4, league.code!!); join(u5, league.code!!)
        for (u in listOf(u2, u4, u5)) chat.report(u, first.message.id, "abuse")
        assertTrue(chatRepo.find(first.message.id)!!.deleted, "three reports hide the line")
        assertFailsWith<AuthorizationException> { chat.delete(u2, chat.list(u1, league.id, null, null).last().message.id) }
        Unit
    }

    @Test
    fun `creator restarts a private season and can remove members, the league passes on when the creator leaves`() = runBlocking {
        val (u1, _) = user("Alpha"); val (u2, b) = user("Bravo"); val (u3, c) = user("Charlie")
        val league = createPrivate(u1, "Office"); join(u2, league.code!!); join(u3, league.code!!)
        runner.runDueRounds(Instant.parse("2026-10-09T20:00:00Z"))
        val restarted = admin.restart(u1, league.id)
        assertEquals(2, restarted.seasonNumber)
        val history = admin.history(u2, league.id)
        assertEquals(1, history.size); assertEquals(1, history.single().seasonNumber)
        assertFailsWith<AuthorizationException> { admin.restart(u2, league.id) }
        admin.kick(u1, league.id, c.id)
        assertFalse(c.id in leagues.members(league.id))
        leave(u1, league.id)
        assertEquals(b.id, leagues.rows.getValue(league.id).createdBy)
        assertEquals(1, social.invites.count { it.kind == InviteKind.LEAGUE && it.inviteeClubId == b.id })
    }

    @Test
    fun `a challenge link is played once by the first other club`() = runBlocking {
        val (u1, a) = user("Alpha"); val (u2, b) = user("Bravo"); val (u3, _) = user("Charlie")
        val ch = challenges.create(u1)
        assertEquals(8, ch.code.length)
        assertFailsWith<ValidationException> { challenges.accept(u1, ch.code) }
        val before = clubs.rows.getValue(a.id).coins
        val m = challenges.accept(u2, ch.code)
        assertEquals(FixtureKind.CHALLENGE, m.fixture.kind); assertEquals(a.id, m.fixture.homeClubId); assertEquals(b.id, m.fixture.awayClubId)
        assertTrue(clubs.rows.getValue(a.id).coins > before, "both clubs get the friendly reward")
        assertFailsWith<ConflictException> { challenges.accept(u3, ch.code) }
        assertEquals(ChallengeStatus.ACCEPTED, challenges.view(ch.code).challenge.status)
        assertEquals(1, social.invites.count { it.kind == InviteKind.CHALLENGE })
        val record = h2h(u2, a.id)
        assertEquals(1, record.won + record.drawn + record.lost)
        clock.advanceSeconds(8 * 86_400)
        val old = challenges.create(u1); clock.advanceSeconds(8 * 86_400)
        assertFailsWith<ConflictException> { challenges.accept(u2, old.code) }
        Unit
    }

    @Test
    fun `profanity filter keeps clean text and masks whole words`() {
        assertEquals("Good game, well played", ProfanityFilter.clean("Good game, well played"))
        assertEquals("ну ***** ты", ProfanityFilter.clean("ну блядь ты"))
    }
}
