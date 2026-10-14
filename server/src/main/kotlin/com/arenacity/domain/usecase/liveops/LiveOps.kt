package com.arenacity.domain.usecase.liveops

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.exception.*
import com.arenacity.domain.model.Club
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.economy.CosmeticsService
import com.arenacity.domain.usecase.economy.ProgressTracker
import java.time.*
import java.util.UUID

// ---------------------------------------------------------------------------------------------------------------------
// Live game (GAME-PLAN phase 5): events the server switches on without a build — a calendar of recurring and seasonal
// events plus any the admin adds — with modifiers (shorter constructions, more income, more gold in obstacles) and a
// goal with a reward; mail from the developers with gifts; the store's starter pack and daily offers.

/** An event: [id] stable ("weekend-2026-10-10", "halloween-2026", or an admin event's UUID). Percentages: 100 = no change. */
data class GameEvent(
    val id: String, val key: String, val startsAt: Instant, val endsAt: Instant,
    val buildTimePct: Int = 100, val incomePct: Int = 100, val obstacleGoldPct: Int = 100,
    val goal: ProgressEvent? = null, val goalTarget: Int = 0, val rewardCoins: Long = 0, val rewardGold: Int = 0, val rewardItem: String? = null,
)
data class EventProgress(val eventId: String, val count: Int, val claimed: Boolean)
/** What the running events do to the game right now. */
data class Modifiers(val buildTimePct: Int = 100, val incomePct: Int = 100, val obstacleGoldPct: Int = 100) {
    fun build(seconds: Long) = if (seconds <= 0) seconds else maxOf(1L, seconds * buildTimePct / 100)
}

data class DevMail(val id: UUID, val title: String, val body: String, val coins: Long, val gold: Int, val item: String?, val clubId: UUID?, val createdAt: Instant, val expiresAt: Instant)

interface LiveOpsRepository {
    suspend fun adminEvents(): List<GameEvent>
    suspend fun createEvent(e: GameEvent)
    suspend fun deleteEvent(id: String)
    suspend fun progress(clubId: UUID, ids: Collection<String>): Map<String, EventProgress>
    suspend fun bump(clubId: UUID, eventId: String, cap: Int)
    suspend fun claimEvent(clubId: UUID, eventId: String, at: Instant): Boolean

    suspend fun mails(clubId: UUID, now: Instant): List<DevMail>
    suspend fun claimedMails(clubId: UUID): Set<UUID>
    suspend fun createMail(m: DevMail)
    suspend fun claimMail(clubId: UUID, mailId: UUID, at: Instant): Boolean

    suspend fun offerBought(clubId: UUID, day: Int, offerId: String): Boolean
    suspend fun markOffer(clubId: UUID, day: Int, offerId: String, at: Instant): Boolean
}

/** The built-in calendar: every weekend, every Wednesday, Halloween, the New Year (UTC days). */
object EventCalendar {
    private fun day(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant()

    /** Built-in events that overlap [from, to). */
    fun between(from: Instant, to: Instant): List<GameEvent> {
        val out = mutableListOf<GameEvent>()
        var d = from.atZone(ZoneOffset.UTC).toLocalDate().minusDays(14)
        val end = to.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1)
        while (!d.isAfter(end)) {
            if (d.dayOfWeek == DayOfWeek.SATURDAY)
                out += GameEvent("weekend-$d", "city_weekend", day(d), day(d.plusDays(2)), incomePct = 150,
                    goal = ProgressEvent.INCOME_COLLECTED, goalTarget = 10, rewardCoins = 1_000, rewardGold = 10)
            if (d.dayOfWeek == DayOfWeek.WEDNESDAY)
                // the goals that were obstacles to clear (none turn up since 14 October, pass 3, Obstacles.ENABLED): collections
                out += GameEvent("builders-$d", "builders_day", day(d), day(d.plusDays(1)), buildTimePct = 70,
                    goal = if (Obstacles.ENABLED) ProgressEvent.OBSTACLE_CLEARED else ProgressEvent.INCOME_COLLECTED, goalTarget = if (Obstacles.ENABLED) 3 else 6, rewardGold = 15)
            if (d.monthValue == 10 && d.dayOfMonth == 24)
                out += GameEvent("halloween-${d.year}", "halloween", day(d), day(d.plusDays(10)), obstacleGoldPct = 200,
                    goal = if (Obstacles.ENABLED) ProgressEvent.OBSTACLE_CLEARED else ProgressEvent.INCOME_COLLECTED, goalTarget = if (Obstacles.ENABLED) 5 else 15,
                    rewardGold = 30, rewardItem = "decor_pumpkins")
            if (d.monthValue == 12 && d.dayOfMonth == 24)
                out += GameEvent("newyear-${d.year}", "new_year", day(d), day(d.plusDays(15)), incomePct = 125,
                    goal = ProgressEvent.LIVE_WON, goalTarget = 10, rewardGold = 50, rewardItem = "decor_snow_tree")
            d = d.plusDays(1)
        }
        return out.filter { it.endsAt.isAfter(from) && it.startsAt.isBefore(to) }
    }
}

data class EventView(val event: GameEvent, val progress: EventProgress?, val running: Boolean)
data class Offer(val id: String, val kind: String, val gold: Int, val coins: Long = 0, val item: String? = null, val oldGold: Int = 0, val bought: Boolean = false)
data class OffersView(val starterAvailable: Boolean, val daily: List<Offer>, val endsAt: Instant)

class LiveOps(
    private val service: ClubService,
    private val clubs: ClubRepository,
    private val repo: LiveOpsRepository,
    private val cosmetics: CosmeticsService,
    private val clock: Clock,
    private val tx: Tx = NoTx,
    private val purchases: (suspend (UUID, String) -> Boolean)? = null,
) {
    @Volatile private var cached: Pair<Instant, List<GameEvent>>? = null
    /** Admin events, re-read at most every 30 s (they are read on every club view). */
    private suspend fun admin(now: Instant): List<GameEvent> {
        cached?.let { (at, list) -> if (now.epochSecond - at.epochSecond < 30) return list }
        return repo.adminEvents().also { cached = now to it }
    }

    suspend fun running(now: Instant = clock.now()): List<GameEvent> =
        (EventCalendar.between(now, now.plusSeconds(1)) + admin(now).filter { !it.startsAt.isAfter(now) && it.endsAt.isAfter(now) })

    suspend fun modifiers(now: Instant = clock.now()): Modifiers {
        val ev = running(now)
        if (ev.isEmpty()) return Modifiers()
        return Modifiers(ev.minOf { it.buildTimePct }, ev.maxOf { it.incomePct }, ev.maxOf { it.obstacleGoldPct })
    }

    /** Running events and the next two weeks', with the club's progress on their goals. */
    suspend fun list(userId: UUID): List<EventView> {
        val club = service.clubOf(userId); val now = clock.now()
        val all = (EventCalendar.between(now.minusSeconds(86_400), now.plusSeconds(14 * 86_400L)) + repo.adminEvents().filter { it.endsAt.isAfter(now.minusSeconds(86_400)) })
            .filter { it.endsAt.isAfter(now) }.sortedBy { it.startsAt }
        val prog = repo.progress(club.id, all.map { it.id })
        return all.map { EventView(it, prog[it.id], !it.startsAt.isAfter(now)) }
    }

    /** Fed from the progress events: counts toward every running event whose goal it is. */
    suspend fun record(clubId: UUID, event: ProgressEvent) {
        for (e in running()) if (e.goal == event) repo.bump(clubId, e.id, e.goalTarget)
    }

    data class EventReward(val coins: Long, val gold: Int, val item: String?, val club: Club)

    suspend fun claim(userId: UUID, eventId: String): EventReward = tx {
        val club = service.lockedClubOf(userId); val now = clock.now()
        val e = (EventCalendar.between(now.minusSeconds(3 * 86_400L), now.plusSeconds(1)) + repo.adminEvents()).firstOrNull { it.id == eventId } ?: throw NotFoundException("Event not found")
        if (e.goal == null) throw ValidationException("Nothing to claim")
        val p = repo.progress(club.id, listOf(e.id))[e.id]
        if ((p?.count ?: 0) < e.goalTarget) throw ValidationException("Not reached yet")
        if (!repo.claimEvent(club.id, e.id, now)) throw ConflictException("Already claimed")
        var gold = e.rewardGold; var item: String? = null
        e.rewardItem?.let { id -> Cosmetics.find(id)?.let { c -> if (cosmetics.grant(club.id, c, CosmeticSource.PASS)) item = c.id else gold += Cosmetics.DUPLICATE_GOLD } }
        val saved = clubs.update(club.copy(coins = club.coins + e.rewardCoins, gold = club.gold + gold, updatedAt = now))
        EventReward(e.rewardCoins, gold, item, saved)
    }

    // ---- admin ----
    suspend fun createEvent(key: String, startsAt: Instant, endsAt: Instant, buildTimePct: Int, incomePct: Int, obstacleGoldPct: Int,
                            goal: String?, goalTarget: Int, rewardCoins: Long, rewardGold: Int, rewardItem: String?): GameEvent {
        if (!endsAt.isAfter(startsAt)) throw ValidationException("The event must end after it starts")
        if (key.isBlank() || key.length > 32) throw ValidationException("Bad event key")
        val g = goal?.let { runCatching { ProgressEvent.valueOf(it) }.getOrElse { throw ValidationException("Unknown goal") } }
        if (rewardItem != null && Cosmetics.find(rewardItem) == null) throw ValidationException("Unknown item")
        val e = GameEvent(UUID.randomUUID().toString(), key.trim(), startsAt, endsAt, buildTimePct.coerceIn(10, 100), incomePct.coerceIn(100, 500), obstacleGoldPct.coerceIn(100, 500),
            g, goalTarget.coerceAtLeast(0), rewardCoins.coerceAtLeast(0), rewardGold.coerceIn(0, 1_000), rewardItem)
        repo.createEvent(e); cached = null; return e
    }
    suspend fun adminEvents() = repo.adminEvents()
    suspend fun deleteEvent(id: String) { repo.deleteEvent(id); cached = null }

    // ---- developer mail ----
    data class MailView(val mail: DevMail, val claimed: Boolean)
    suspend fun mail(userId: UUID): List<MailView> {
        val club = service.clubOf(userId); val claimed = repo.claimedMails(club.id)
        return repo.mails(club.id, clock.now()).map { MailView(it, it.id in claimed) }
    }
    suspend fun claimMail(userId: UUID, mailId: UUID): EventReward = tx {
        val club = service.lockedClubOf(userId); val now = clock.now()
        val m = repo.mails(club.id, now).firstOrNull { it.id == mailId } ?: throw NotFoundException("Mail not found")
        if (m.coins == 0L && m.gold == 0 && m.item == null) throw ValidationException("Nothing to claim")
        if (!repo.claimMail(club.id, m.id, now)) throw ConflictException("Already claimed")
        var gold = m.gold; var item: String? = null
        m.item?.let { id -> Cosmetics.find(id)?.let { c -> if (cosmetics.grant(club.id, c, CosmeticSource.PASS)) item = c.id else gold += Cosmetics.DUPLICATE_GOLD } }
        val saved = clubs.update(club.copy(coins = club.coins + m.coins, gold = club.gold + gold, updatedAt = now))
        EventReward(m.coins, gold, item, saved)
    }
    suspend fun sendMail(title: String, body: String, coins: Long, gold: Int, item: String?, clubId: UUID?, days: Int): DevMail {
        if (title.isBlank() || title.length > 80 || body.length > 1_000) throw ValidationException("Bad mail")
        if (item != null && Cosmetics.find(item) == null) throw ValidationException("Unknown item")
        val now = clock.now()
        val m = DevMail(UUID.randomUUID(), title.trim(), body.trim(), coins.coerceIn(0, 10_000_000), gold.coerceIn(0, 10_000), item, clubId, now, now.plusSeconds(days.coerceIn(1, 60) * 86_400L))
        repo.createMail(m); return m
    }

    // ---- offers ----
    private fun dayKey(now: Instant) = now.atZone(ZoneOffset.UTC).toLocalDate().let { it.year * 1000 + it.dayOfYear }

    /** Three offers a day for gold, the same for a club all day: a discounted coin pack (sized by the stadium), a look
     * at a third off, and a builder's rush (gold for a fixed chunk of coins at the best rate). */
    suspend fun offers(userId: UUID): OffersView {
        val st = service.state(userId); val now = clock.now(); val day = dayKey(now)
        val rnd = java.util.Random(day.toLong() * 31 + st.club.id.leastSignificantBits)
        val coinsK = Stadium.MAX_LEVEL.let { 1L shl (st.stadium - 1).coerceIn(0, 9) }.coerceAtMost(256)
        val pack = Offer("coins-$day", "COINS", 60, coins = 6_000L * coinsK.coerceAtLeast(1), oldGold = 100)
        val owned = cosmetics.owned(st.club.id)
        val shopLooks = Cosmetics.ALL.filter { it.source == CosmeticSource.SHOP && it.id !in owned }
        val look = shopLooks.takeIf { it.isNotEmpty() }?.let { it[rnd.nextInt(it.size)] }?.let { Offer("look-$day", "LOOK", (it.gold * 2 / 3).coerceAtLeast(10), item = it.id, oldGold = it.gold) }
        val rush = Offer("rush-$day", "COINS", 150, coins = 20_000L * coinsK.coerceAtLeast(1), oldGold = 250)
        val list = listOfNotNull(pack, look, rush).map { it.copy(bought = repo.offerBought(st.club.id, day, it.id)) }
        val starter = purchases?.invoke(st.club.id, STARTER_PACK) == false
        val tomorrow = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        return OffersView(starter, list, tomorrow)
    }

    suspend fun buyOffer(userId: UUID, offerId: String): Club = tx {
        val view = offers(userId)
        val o = view.daily.firstOrNull { it.id == offerId } ?: throw NotFoundException("Offer not found")
        if (o.bought) throw ConflictException("Already bought")
        val club = service.lockedClubOf(userId); val now = clock.now()
        if (club.gold < o.gold) throw InsufficientFundsException("Not enough gold")
        if (!repo.markOffer(club.id, dayKey(now), o.id, now)) throw ConflictException("Already bought")
        o.item?.let { id -> Cosmetics.find(id)?.let { cosmetics.grant(club.id, it, CosmeticSource.SHOP) } }
        clubs.update(club.copy(gold = club.gold - o.gold, coins = club.coins + o.coins, updatedAt = now))
    }

    companion object { const val STARTER_PACK = "starter_pack" }
}

/** Forwards progress events to the running events' goals. */
class EventProgressTracker(private val ops: () -> LiveOps) : ProgressTracker {
    override suspend fun record(clubId: UUID, event: ProgressEvent) { runCatching { ops().record(clubId, event) } }
}
