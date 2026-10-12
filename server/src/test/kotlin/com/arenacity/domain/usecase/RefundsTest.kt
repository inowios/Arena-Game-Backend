package com.arenacity.domain.usecase

import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.account.InboxService
import com.arenacity.domain.usecase.athlete.StarterSquadInitializer
import com.arenacity.domain.usecase.club.*
import com.arenacity.domain.usecase.economy.*
import com.arenacity.domain.usecase.league.*
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class RefundsTest {
    private val clock = FixedClock(Instant.parse("2026-10-10T10:00:00Z"))
    private val users = FakeUserRepository(); private val clubs = FakeClubRepository(); private val buildings = FakeBuildingRepository(); private val athletes = FakeAthleteRepository()
    private val leagues = FakeLeagueRepository(); private val fixtures = FakeFixtureRepository(); private val repo = FakeEconomyRepository()
    private val inventory = FakeInventoryRepository(); private val inboxRepo = FakeInboxRepository()
    private val bots = BotService(users, clubs, athletes, clock)
    private val leagueService = LeagueService(leagues, fixtures, clubs, bots, clock)
    private val clubService = ClubService(clubs, buildings, clock)
    private val economy = EconomyService(repo, leagues, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock), JoinPublicLeagueInitializer(clubs, leagueService, clock)))
    private val pass = SeasonPassUseCases(clubService, clubs, repo, economy, clock)
    private val cosmetics = CosmeticsService(inventory, clock)
    private val purchase = VerifyPurchaseUseCase(clubService, clubs, repo, pass, mapOf(Store.DEV to DevReceiptVerifier()), clock, cosmetics = cosmetics)
    private val refund = RefundUseCase(clubs, repo, inventory, clock, inbox = InboxService(inboxRepo, clock))
    private val user = UUID.randomUUID()

    private suspend fun club() = clubService.clubOf(user)
    private suspend fun buy(product: String, order: String) = purchase(user, Store.DEV, product, "dev:$order").also { assertEquals("GRANTED", it.status) }
    private fun key(product: String, order: String) = "dev:$product:$order"
    private fun data(m: com.arenacity.domain.model.InboxItem): Map<String, String> =
        kotlinx.serialization.json.Json.parseToJsonElement(m.dataJson).jsonObject.mapValues { it.value.jsonPrimitive.content }

    @Test
    fun `a refund takes the gold back, is idempotent, and a reversed refund gives it back`() = runBlocking<Unit> {
        create(user, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val g0 = club().gold
        buy("gold_small", "o1"); assertEquals(g0 + 100, club().gold)

        assertEquals(RefundOutcome.APPLIED, refund(RefundAction.REFUND, listOf("google:unknown", key("gold_small", "o1"))))
        assertEquals(g0, club().gold)
        assertEquals("REFUNDED", repo.purchaseByExternalId(key("gold_small", "o1"))!!.status)
        val mail = inboxRepo.rows.single(); assertEquals("REFUND", mail.kind); assertEquals("100", data(mail)["gold"]); assertEquals("gold_small", data(mail)["product"])
        assertEquals(RefundOutcome.ALREADY, refund(RefundAction.REFUND, listOf(key("gold_small", "o1"))))
        assertEquals(g0, club().gold)

        assertEquals(RefundOutcome.APPLIED, refund(RefundAction.RESTORE, listOf(key("gold_small", "o1"))))
        assertEquals(g0 + 100, club().gold)
        assertEquals("GRANTED", repo.purchaseByExternalId(key("gold_small", "o1"))!!.status)
        assertEquals("REFUND_REVERSED", inboxRepo.rows.last().kind)
        assertEquals(RefundOutcome.ALREADY, refund(RefundAction.RESTORE, listOf(key("gold_small", "o1"))))
        assertEquals(RefundOutcome.UNKNOWN, refund(RefundAction.REFUND, listOf("apple:nope")))
        // the refunded purchase stays known to the store: the same receipt is still a duplicate
        assertEquals("DUPLICATE", purchase(user, Store.DEV, "gold_small", "dev:o1").status)
    }

    @Test
    fun `gold already spent stays spent, the club never goes below zero`() = runBlocking<Unit> {
        create(user, "Lions", "#AA0000", "#FFFFFF", "UTC")
        buy("gold_small", "o2")
        clubs.update(club().copy(gold = 30))
        assertEquals(RefundOutcome.APPLIED, refund(RefundAction.REFUND, listOf(key("gold_small", "o2"))))
        assertEquals(0, club().gold)
        assertEquals("30", data(inboxRepo.rows.single())["gold"])
        // the reversal gives back what the purchase granted, not what was taken
        refund(RefundAction.RESTORE, listOf(key("gold_small", "o2"))); assertEquals(100, club().gold)
    }

    @Test
    fun `the starter pack refund removes its item and lets the club buy it again`() = runBlocking<Unit> {
        create(user, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val c0 = club()
        buy("starter_pack", "o3")
        assertTrue(inventory.items(c0.id).any { it.itemId == "decor_fountain" })
        assertEquals("ALREADY_OWNED", purchase(user, Store.DEV, "starter_pack", "dev:o4").status)

        assertEquals(RefundOutcome.APPLIED, refund(RefundAction.REFUND, listOf(key("starter_pack", "o3"))))
        assertFalse(inventory.items(c0.id).any { it.itemId == "decor_fountain" })
        assertEquals(c0.gold, club().gold); assertEquals(c0.coins, club().coins)
        assertEquals("GRANTED", purchase(user, Store.DEV, "starter_pack", "dev:o4").status)
        assertTrue(inventory.items(c0.id).any { it.itemId == "decor_fountain" })

        // reversing the first refund while the second purchase stands does not duplicate the item
        refund(RefundAction.RESTORE, listOf(key("starter_pack", "o3")))
        assertEquals(1, inventory.items(c0.id).count { it.itemId == "decor_fountain" })
    }

    @Test
    fun `the premium pass is switched off by a refund and on by its reversal`() = runBlocking<Unit> {
        create(user, "Lions", "#AA0000", "#FFFFFF", "UTC")
        val c = club()
        buy("season_pass", "o5"); assertTrue(repo.pass(c.id, c.seasonNo)!!.premium)
        refund(RefundAction.REFUND, listOf(key("season_pass", "o5"))); assertFalse(repo.pass(c.id, c.seasonNo)!!.premium)
        refund(RefundAction.RESTORE, listOf(key("season_pass", "o5"))); assertTrue(repo.pass(c.id, c.seasonNo)!!.premium)
    }
}
