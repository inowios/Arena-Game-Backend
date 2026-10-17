package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.AnalyticsEvent
import com.arenacity.domain.model.ClubSnapshot
import com.arenacity.domain.usecase.analytics.BalanceUseCase
import com.arenacity.domain.usecase.analytics.FunnelUseCase
import com.arenacity.presentation.dto.toDto
import com.arenacity.testutil.FakeAnalyticsRepository
import com.arenacity.testutil.FixedClock
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class DashboardsTest {
    private val clock = FixedClock(Instant.parse("2026-10-20T12:00:00Z"))
    private val repo = FakeAnalyticsRepository()
    private val t0 = Instant.parse("2026-10-18T10:00:00Z")

    private fun ev(u: UUID, name: String, sec: Long, vararg props: Pair<String, String>) = AnalyticsEvent(u, name, props.toMap(), "ios", "1", t0.plusSeconds(sec))
    private fun steps(u: UUID, upTo: String) {
        repo.events += ev(u, "club_created", 0)
        repo.events += ev(u, "tutorial_start", 5)
        val all = listOf("collect", "build", "squad", "lineup", "play", "reward", "upgrade", "league")
        for ((i, s) in all.withIndex()) {
            repo.events += ev(u, "tutorial_step", 60L * (i + 1), "step" to s)
            if (s == upTo) return
        }
        repo.events += ev(u, "tutorial_done", 600)
    }

    @Test
    fun `funnel counts each step over the period's new clubs`() = runBlocking<Unit> {
        val a = UUID.randomUUID(); val b = UUID.randomUUID(); val c = UUID.randomUUID()
        steps(a, "none")                 // finishes
        steps(b, "squad")                // stops after squad
        repo.events += ev(c, "club_created", 0)
        repo.events += ev(c, "tutorial_start", 3)
        repo.events += ev(c, "tutorial_skip", 30, "step" to "collect")
        // an old club outside the period does not count, even with fresh events
        val old = UUID.randomUUID()
        repo.events += AnalyticsEvent(old, "club_created", emptyMap(), null, null, Instant.parse("2026-09-01T00:00:00Z"))
        repo.events += ev(old, "tutorial_step", 10, "step" to "collect")

        val r = FunnelUseCase(repo, clock)(days = 7)
        assertEquals(3, r.cohort)
        val by = r.steps.associateBy { it.step }
        assertEquals(FunnelUseCase.STEPS, r.steps.map { it.step })
        assertEquals(3, by.getValue("club_created").users)
        assertEquals(3, by.getValue("tutorial_start").users)
        assertEquals(2, by.getValue("collect").users)
        assertEquals(2, by.getValue("squad").users)
        assertEquals(1, by.getValue("lineup").users)
        assertEquals(0.5, by.getValue("lineup").fromPrevious)
        assertEquals(1, by.getValue("tutorial_done").users)
        assertEquals(1, r.finished)
        assertEquals(1, r.skipped)
        assertEquals(1, by.getValue("collect").skippedHere)
        assertEquals(60, by.getValue("collect").medianSeconds)
        assertEquals(0.3333, by.getValue("tutorial_done").share)
        assertEquals("club_created", r.toDto().steps.first().step)
    }

    @Test
    fun `a step put off with later is counted as reached and as put off`() = runBlocking<Unit> {
        val u = UUID.randomUUID()
        repo.events += ev(u, "club_created", 0)
        repo.events += ev(u, "tutorial_step", 400, "step" to "upgrade", "later" to "1")
        val r = FunnelUseCase(repo, clock)(days = 7)
        val up = r.steps.first { it.step == "upgrade" }
        assertEquals(1, up.users); assertEquals(1, up.putOff); assertEquals(400, up.medianSeconds)
    }

    @Test
    fun `median of even and odd lists`() {
        assertEquals(null, FunnelUseCase.median(emptyList()))
        assertEquals(5, FunnelUseCase.median(listOf(9, 5, 1)))
        assertEquals(4, FunnelUseCase.median(listOf(1, 3, 5, 7)))
    }

    @Test
    fun `balance reads arena matches, obstacles, purchases and the active clubs`() = runBlocking<Unit> {
        val u = UUID.randomUUID()
        repeat(4) { repo.events += ev(u, "live_kickoff", it.toLong(), "control" to if (it < 3) "stick" else "auto", "bot" to "1") }
        repo.events += ev(u, "live_finish", 10, "outcome" to "win")
        repo.events += ev(u, "live_finish", 11, "outcome" to "win")
        repo.events += ev(u, "live_finish", 12, "outcome" to "loss")
        repo.events += ev(u, "purchase_start", 15, "product" to "starter_pack")
        repo.events += ev(u, "stadium_level", 16, "level" to "2")
        repo.clubs = (1..10).map { ClubSnapshot(it * 1_000L, it * 10, it * 100, it, if (it > 7) 2 else 1) }
        repo.spots = mapOf("academy" to 3, "beast_dome" to 1)

        val r = BalanceUseCase(repo, clock)(days = 7)
        assertEquals(4, r.live.kickoffs); assertEquals(3, r.live.finishes); assertEquals(0.75, r.live.finishRate)
        assertEquals(mapOf("loss" to 1, "win" to 2), r.live.outcomes)
        assertEquals(mapOf("auto" to 1, "stick" to 3), r.live.controls)
        assertEquals(4, r.live.vsBots)
        assertEquals(mapOf("starter_pack" to 1), r.purchaseStarts)
        assertEquals(mapOf(2 to 1), r.stadiumUps)
        assertEquals(10, r.activeClubs)
        assertEquals(5_000, r.coins!!.p50); assertEquals(10_000, r.coins!!.max); assertEquals(1_000, r.coins!!.p10)
        assertEquals(mapOf(1 to 7, 2 to 3), r.stadium)
        assertEquals(mapOf("academy" to 3, "beast_dome" to 1), r.toDto().spots)
    }

    @Test
    fun `report periods are bounded`() = runBlocking<Unit> {
        assertFailsWith<ValidationException> { FunnelUseCase(repo, clock)(days = 0) }
        assertFailsWith<ValidationException> { BalanceUseCase(repo, clock)(days = 91) }
        assertNull(BalanceUseCase(repo, clock)(days = 7).coins)
    }
}
