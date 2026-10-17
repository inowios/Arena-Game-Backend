package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.model.AuthProvider
import com.arenacity.domain.model.LeagueKind
import com.arenacity.domain.usecase.account.*
import com.arenacity.domain.usecase.athlete.StarterSquadInitializer
import com.arenacity.domain.usecase.auth.GuestLoginUseCase
import com.arenacity.domain.usecase.auth.TokenIssuer
import com.arenacity.domain.usecase.club.*
import com.arenacity.domain.usecase.league.*
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.*

class AccountTest {
    private val clock = FixedClock(Instant.parse("2026-10-09T10:00:00Z"))
    private val users = FakeUserRepository(); private val refresh = FakeRefreshTokenRepository(); private val clubs = FakeClubRepository()
    private val athletes = FakeAthleteRepository(); private val leagues = FakeLeagueRepository(); private val fixtures = FakeFixtureRepository()
    private val accounts = FakeAccountRepository(); private val inboxRepo = FakeInboxRepository()
    private val issuer = TokenIssuer(refresh, testJwt, clock)
    private val guest = GuestLoginUseCase(users, issuer, clock)
    private val bots = BotService(users, clubs, athletes, clock)
    private val leagueService = LeagueService(leagues, fixtures, clubs, bots, clock)
    private val create = CreateClubUseCase(clubs, clock, listOf(StarterSquadInitializer(athletes, clock), JoinPublicLeagueInitializer(clubs, leagueService, clock)))
    private val retirement = ClubRetirement(clubs, leagues, accounts, clock)
    private val transfer = TransferUseCases(users, clubs, accounts, issuer, retirement, clock)
    private val delete = DeleteAccountUseCase(users, clubs, accounts, refresh, retirement, clock)
    private val inbox = InboxService(inboxRepo, clock)

    @Test
    fun `a transfer code moves a guest account to a new device once`() = runBlocking<Unit> {
        val old = guest("device-old-0001").user
        val club = create(old.id, "Movers FC", "#AA0000", "#FFFFFF", "UTC")
        val fresh = guest("device-new-0002").user                       // the new phone already made its own guest
        create(fresh.id, "Fresh FC", "#00AA00", "#FFFFFF", "UTC")
        val code = transfer.createCode(old.id)
        assertEquals(8, code.code.length)
        val r = transfer.redeem(TransferUseCases.display(code.code).lowercase(), "device-new-0002")
        assertEquals(old.id, r.user.id)
        assertEquals("device-new-0002", users.rows.getValue(old.id).providerId)
        assertFalse(users.rows.getValue(fresh.id).isActive)
        assertTrue(clubs.rows.values.first { it.userId == fresh.id }.isBot, "the detached guest's club retires into a bot")
        assertEquals(club.id, clubs.findByUserId(old.id)!!.id)
        assertEquals(old.id, guest("device-new-0002").user.id, "guest login on the new device now opens the moved account")
        assertFailsWith<ConflictException> { transfer.redeem(code.code, "device-third-03") }
        assertFailsWith<NotFoundException> { transfer.redeem("ZZZZ-ZZZZ", "device-third-03") }
        val late = transfer.createCode(old.id); clock.advanceSeconds(TransferUseCases.TTL_SECONDS + 1)
        assertFailsWith<ConflictException> { transfer.redeem(late.code, "device-third-03") }
        Unit
    }

    @Test
    fun `deleting the account erases sign-in data and retires the club`() = runBlocking<Unit> {
        val u = guest("device-del-0001").user
        val club = create(u.id, "Leavers FC", "#AA0000", "#FFFFFF", "UTC")
        val friend = guest("device-del-0002").user; val friendClub = create(friend.id, "Stayers FC", "#0000AA", "#FFFFFF", "UTC")
        val priv = CreatePrivateLeagueUseCase(ClubService(clubs, clock), leagues, clock)(u.id, "Office")
        JoinPrivateLeagueUseCase(ClubService(clubs, clock), leagues)(friend.id, priv.code!!)
        delete(u.id)
        val gone = users.rows.getValue(u.id)
        assertFalse(gone.isActive); assertTrue(gone.providerId.startsWith("deleted:")); assertNull(gone.email)
        assertTrue(refresh.rows.values.filter { it.userId == u.id }.all { it.revokedAt != null })
        assertTrue(u.id in accounts.deletedPushFor); assertTrue(club.id in accounts.hiddenFor)
        val bot = clubs.rows.getValue(club.id)
        assertTrue(bot.isBot); assertNotEquals("Leavers FC", bot.name)
        assertFalse(club.id in leagues.members(priv.id)); assertEquals(friendClub.id, leagues.findById(priv.id)!!.createdBy, "the league passes to a remaining member")
        assertTrue(leagues.listForClub(club.id).any { it.kind == LeagueKind.PUBLIC }, "the public league keeps its member as a bot")
        val again = guest("device-del-0001")
        assertNotEquals(u.id, again.user.id); assertTrue(again.isNewUser, "the same phone starts a brand-new account")
        Unit
    }

    @Test
    fun `inbox keeps entries and counts unread`() = runBlocking<Unit> {
        val u = guest("device-inbox-01").user; val club = create(u.id, "Inbox FC", "#AA0000", "#FFFFFF", "UTC")
        inbox.add(club.id, "MATCHDAY", mapOf("opponent" to "FC Rivals", "verdict" to "WIN"))
        clock.advanceSeconds(60); inbox.add(club.id, "LEAGUE_JOIN", mapOf("league" to "Office", "club" to "Bravo"))
        assertEquals(2, inbox.unread(club.id))
        assertEquals("LEAGUE_JOIN", inbox.list(club.id).first().kind)
        inbox.markAllRead(club.id); assertEquals(0, inbox.unread(club.id))
    }
}
