package com.arenacity.domain.usecase.cup

import com.arenacity.common.i18n.ServerI18n
import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.core.push.FcmSender
import com.arenacity.domain.model.*
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.repository.CupRepository
import com.arenacity.domain.repository.FixtureRepository
import com.arenacity.domain.repository.SeasonRepository
import com.arenacity.domain.rules.ClubLevels
import com.arenacity.domain.rules.Cups
import com.arenacity.domain.rules.Divisions
import com.arenacity.domain.rules.LeagueRules
import com.arenacity.common.ops.FailureSink
import com.arenacity.common.ops.NoFailures
import com.arenacity.domain.usecase.account.InboxService
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.league.BotService
import com.arenacity.domain.usecase.league.MatchdayRunner
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random

/** A played tie with its stored fixture and result. */
data class DecidedTie(val tie: CupTie, val fixture: Fixture, val result: MatchdayResult?) {
    val winner get() = tie.winnerClubId!!
    val loser get() = if (winner == tie.homeClubId) tie.awayClubId else tie.homeClubId
}

/**
 * Weekly cups (see [Cups]): the scheduler calls [tick] every pass — due draws first, then every due round of active
 * cups, catching up in order after downtime. Each draw and each round is one transaction (cup row locked; fixtures are
 * marked played only once), pushes and inbox lines go out after commit.
 */
class CupService(
    private val cups: CupRepository,
    private val clubs: ClubRepository,
    private val fixtures: FixtureRepository,
    private val bots: BotService,
    private val runner: MatchdayRunner,
    private val seasons: SeasonRepository,
    private val clock: Clock,
    private val tx: Tx = NoTx,
    private val notifier: CupNotifier? = null,
    private val failures: FailureSink = NoFailures,
) {
    private val log = LoggerFactory.getLogger("Cups")

    /** @return cup matches played. */
    suspend fun tick(now: Instant = clock.now()): Int {
        try { drawDue(now) } catch (e: Exception) { log.error("cup draw failed", e); failures.report("cup_draw", e) }
        var played = 0
        for (cup in cups.active()) {
            try { played += run(cup.id, now) } catch (e: Exception) { log.error("cup ${cup.id} failed", e); failures.report("cup", e) }
        }
        return played
    }

    /**
     * Draws every bucket whose Thursday draw time has passed this week (until the final) and has no cup yet. Clubs are
     * bucketed by their zone's offset today, so daylight-saving changes move them with their clock.
     */
    suspend fun drawDue(now: Instant): List<Cup> {
        val since = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(Cups.ACTIVE_DAYS - 1)
        val drawn = mutableListOf<Cup>()
        val active = cups.activeHumanClubs(since).groupBy({ (_, tz) -> runCatching { LeagueRules.tzBucket(tz, now) }.getOrDefault(0) }, { it.first })
        for ((bucket, ids) in active.toSortedMap()) {
            val week = Cups.weekStart(now, bucket)
            if (now.isBefore(Cups.drawAt(week, bucket)) || !now.isBefore(Cups.roundAt(week, Cups.ROUNDS, bucket))) continue
            if (cups.cupsOf(bucket, week).isNotEmpty()) continue
            val (made, after) = tx { draw(bucket, week, ids, now) }
            drawn += made
            after.forEach { runCatching { it() }.onFailure { e -> log.warn("cup draw notice failed", e) } }
        }
        return drawn
    }

    private suspend fun draw(bucket: Int, week: LocalDate, clubIds: List<UUID>, now: Instant): Pair<List<Cup>, List<suspend () -> Unit>> {
        if (cups.cupsOf(bucket, week).isNotEmpty()) return emptyList<Cup>() to emptyList()
        val taken = cups.weekEntrants(week).toMutableSet()
        // strongest first, so each cup gathers clubs of a similar level
        val humans = clubs.findByIds(clubIds).filter { !it.isBot && it.id !in taken }
            .sortedWith(compareByDescending<Club> { it.division }.thenByDescending { it.reputation }.thenBy { it.id })
        if (humans.isEmpty()) return emptyList<Cup>() to emptyList()
        val zone = Cups.zone(bucket).id
        val made = mutableListOf<Cup>(); val after = mutableListOf<suspend () -> Unit>()
        humans.chunked(Cups.SIZE).forEachIndexed { i, group ->
            val division = group[group.size / 2].division
            // the cup plays at 18:00 in the zone most of its players live in
            val timezone = group.groupingBy { it.timezone }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first().key
            val names = group.map { it.name }.toMutableSet()
            val field = group.toMutableList()
            for (b in clubs.listBots(bucket).filter { it.division == division && it.id !in taken }.sortedBy { it.id }) {
                if (field.size >= Cups.SIZE) break
                if (b.name in names) continue   // two bots may share a name across leagues; not within one bracket
                field += b; names += b.name
            }
            var k = Math.floorMod(week.toEpochDay().toInt() + i * 5, 24)
            while (field.size < Cups.SIZE) { val b = bots.createUnique(bucket, division, zone, names, k++); field += b; names += b.name }
            taken += field.map { it.id }
            val cupId = UUID.randomUUID()
            val order = field.shuffled(Random(cupId.leastSignificantBits))   // an open draw
            val cup = Cup(cupId, bucket, week, i + 1, division, 0, CupStatus.ACTIVE, null, now, now, timezone)
            val first = (0 until Cups.SIZE / 2).map { s -> newTie(cup, 1, s, order[2 * s].id, order[2 * s + 1].id, now) }
            fixtures.createAll(first.map { it.second })
            cups.create(cup, order.mapIndexed { seed, c -> CupEntry(week, c.id, cupId, bucket, seed, createdAt = now) }, first.map { it.first })
            made += cup
            val byId = order.associateBy { it.id }
            after += { notifier?.drawn(cup, first.map { it.first }, byId) }
        }
        log.info("Cup draw bucket $bucket week $week: ${made.size} cups, ${humans.size} clubs")
        return made to after
    }

    private fun newTie(cup: Cup, round: Int, slot: Int, home: UUID, away: UUID, now: Instant): Pair<CupTie, Fixture> {
        val f = Fixture(UUID.randomUUID(), null, FixtureKind.CUP, 0, round, Cups.roundAt(cup, round), home, away,
            FixtureStatus.SCHEDULED, null, null, Cups.seed(cup.id, round, slot), null, null, now)
        return CupTie(cup.id, round, slot, home, away, f.id) to f
    }

    /** Plays every due round of one cup. @return matches played. */
    suspend fun run(cupId: UUID, now: Instant): Int {
        var played = 0
        repeat(Cups.ROUNDS) {
            val step = tx { round(cupId, now) } ?: return played
            played += step.first
            step.second.forEach { runCatching { it() }.onFailure { e -> log.warn("cup notice failed", e) } }
        }
        return played
    }

    private suspend fun round(cupId: UUID, now: Instant): Pair<Int, List<suspend () -> Unit>>? {
        val cup = cups.lock(cupId)?.takeIf { it.status == CupStatus.ACTIVE } ?: return null
        val r = cup.currentRound + 1
        if (r > Cups.ROUNDS || Cups.roundAt(cup, r).isAfter(now)) return null
        var matches = 0
        val roundTies = cups.ties(cup.id).filter { it.round == r }.sortedBy { it.slot }
        // every club of the round locked up front in one global order: a parallel challenge (sorted pair) can't deadlock with us
        roundTies.flatMap { listOf(it.homeClubId, it.awayClubId) }.distinct().sorted().forEach { clubs.lockById(it) }
        val decided = roundTies.map { t ->
            val f = fixtures.findById(t.fixtureId) ?: error("cup fixture ${t.fixtureId} missing")
            val p = if (f.status == FixtureStatus.SCHEDULED) runner.playFixture(f, now)?.also { matches++ } else null
            val stored = p?.fixture ?: fixtures.findById(t.fixtureId)!!
            val result = p?.result ?: stored.resultJson?.let { MatchdayResult.parse(it) }
            val winner = t.winnerClubId ?: Cups.winner(stored, result).also { cups.setWinner(cup.id, r, t.slot, it) }
            DecidedTie(t.copy(winnerClubId = winner), stored, result)
        }
        val entries = cups.entries(cup.id).associateBy { it.clubId }
        val byId = clubs.findByIds(entries.keys).associateBy { it.id }
        // losers leave with the prize of the round they reached; the final also crowns the champion
        val payees = decided.associate { it.loser to r }.toMutableMap<UUID, Int?>()
        val finished = r == Cups.ROUNDS
        if (finished) payees[decided.single().winner] = null
        val prizes = mutableMapOf<UUID, Divisions.Prize>()
        for (id in payees.keys.sorted()) entries[id]?.let { prizes[id] = pay(it, payees[id], cup, byId[id], now) }
        if (!finished) {
            val next = (0 until decided.size / 2).map { s -> newTie(cup, r + 1, s, decided[2 * s].winner, decided[2 * s + 1].winner, now) }
            fixtures.createAll(next.map { it.second }); cups.addTies(next.map { it.first })
            cups.update(cup.copy(currentRound = r, updatedAt = now))
        } else {
            val champion = decided.single().winner
            byId[champion]?.takeUnless { it.isBot }?.let { seasons.createTrophy(Trophy(UUID.randomUUID(), it.id, TrophyKind.CUP, cup.division, it.seasonNo, now)) }
            cups.update(cup.copy(currentRound = r, status = CupStatus.FINISHED, winnerClubId = champion, updatedAt = now))
        }
        val after: List<suspend () -> Unit> = listOf { notifier?.roundPlayed(cup, r, decided, byId, prizes) }
        return matches to after
    }

    private suspend fun pay(e: CupEntry, outRound: Int?, cup: Cup, club: Club?, now: Instant): Divisions.Prize {
        val human = club != null && !club.isBot
        val prize = if (human) Cups.prize(Cups.stage(outRound), cup.division) else Divisions.Prize(0, 0, 0)
        cups.updateEntry(e.copy(outRound = outRound, prizeCoins = prize.coins, prizeGold = prize.gold, prizeReputation = prize.reputation))
        if (human) clubs.lockById(club!!.id)?.let { c ->
            val rep = c.reputation + prize.reputation
            clubs.update(c.copy(coins = c.coins + prize.coins, gold = c.gold + prize.gold, reputation = rep, level = maxOf(c.level, ClubLevels.levelFor(rep)), updatedAt = now))
        }
        return prize
    }
}

/** Inbox lines and pushes for cup draws and results, in each player's language. */
class CupNotifier(private val push: FcmSender? = null, private val inbox: InboxService? = null) {
    private val log = LoggerFactory.getLogger("CupNotifier")

    suspend fun drawn(cup: Cup, ties: List<CupTie>, clubs: Map<UUID, Club>) {
        for (t in ties) for ((me, opp) in listOf(t.homeClubId to t.awayClubId, t.awayClubId to t.homeClubId)) {
            val club = clubs[me]?.takeUnless { it.isBot } ?: continue
            val other = clubs[opp]?.name ?: "?"
            inbox?.add(club.id, "CUP_DRAW", mapOf("cupId" to cup.id.toString(), "opponent" to other, "at" to Cups.roundAt(cup, 1).toString()))
            // one failing push must not cost the other clubs their notice
            runCatching {
                push?.sendLocalized(listOf(club.userId), mapOf("type" to "CUP", "cupId" to cup.id.toString())) { lang ->
                    ServerI18n.t("push.cup_draw_title", lang) to ServerI18n.t("push.cup_draw_body", lang, club.name, other)
                }
            }.onFailure { log.warn("cup draw push failed for ${club.id}: ${it.message}") }
        }
    }

    /** "Lions 2–1 Wolves" (section points when several sections were played), plus the shootout. */
    fun score(d: DecidedTie, me: UUID, clubs: Map<UUID, Club>, lang: String): String {
        val home = d.tie.homeClubId == me
        val multi = (d.result?.sections?.size ?: 1) > 1
        val f = d.fixture
        val mine = (if (multi) (if (home) f.homePoints else f.awayPoints) else (if (home) f.homeScore else f.awayScore)) ?: 0
        val theirs = (if (multi) (if (home) f.awayPoints else f.homePoints) else (if (home) f.awayScore else f.homeScore)) ?: 0
        val opp = clubs[if (home) d.tie.awayClubId else d.tie.homeClubId]?.name ?: "?"
        val line = ServerI18n.t("push.cup_score", lang, clubs[me]?.name ?: "", mine, theirs, opp)
        val so = d.result?.shootout ?: return line
        return ServerI18n.t("push.cup_pens", lang, line, if (home) so.home else so.away, if (home) so.away else so.home)
    }

    fun message(d: DecidedTie, me: UUID, round: Int, clubs: Map<UUID, Club>, prize: Divisions.Prize?, lang: String): Pair<String, String> {
        val score = score(d, me, clubs, lang)
        fun withPrize() = when {
            prize == null || prize.coins == 0L -> score
            prize.gold > 0 -> ServerI18n.t("push.cup_prize_gold", lang, score, prize.coins, prize.gold)
            else -> ServerI18n.t("push.cup_prize", lang, score, prize.coins)
        }
        return when {
            d.winner != me -> ServerI18n.t("push.cup_out_title", lang) to withPrize()
            round == Cups.ROUNDS -> ServerI18n.t("push.cup_champion_title", lang) to withPrize()
            else -> ServerI18n.t("push.cup_through_${round + 1}", lang) to ServerI18n.t("push.cup_next", lang, score)
        }
    }

    suspend fun roundPlayed(cup: Cup, round: Int, ties: List<DecidedTie>, clubs: Map<UUID, Club>, prizes: Map<UUID, Divisions.Prize>) {
        for (d in ties) for (me in listOf(d.tie.homeClubId, d.tie.awayClubId)) {
            val club = clubs[me]?.takeUnless { it.isBot } ?: continue
            val home = d.tie.homeClubId == me
            val multi = (d.result?.sections?.size ?: 1) > 1
            val f = d.fixture
            val prize = prizes[me]
            val verdict = when { d.winner != me -> "OUT"; round == Cups.ROUNDS -> "CHAMPION"; else -> "THROUGH" }
            inbox?.add(club.id, "CUP_RESULT", buildMap {
                put("cupId", cup.id.toString()); put("fixtureId", f.id.toString()); put("round", round.toString()); put("verdict", verdict)
                put("opponent", clubs[if (home) d.tie.awayClubId else d.tie.homeClubId]?.name ?: "?")
                put("mine", ((if (multi) (if (home) f.homePoints else f.awayPoints) else (if (home) f.homeScore else f.awayScore)) ?: 0).toString())
                put("theirs", ((if (multi) (if (home) f.awayPoints else f.homePoints) else (if (home) f.awayScore else f.homeScore)) ?: 0).toString())
                d.result?.shootout?.let { put("pens", if (home) "${it.home}-${it.away}" else "${it.away}-${it.home}") }
                if (prize != null) { put("coins", prize.coins.toString()); put("gold", prize.gold.toString()) }
            })
            runCatching {
                push?.sendLocalized(listOf(club.userId), mapOf("type" to "CUP", "cupId" to cup.id.toString(), "fixtureId" to f.id.toString())) { lang -> message(d, me, round, clubs, prize, lang) }
            }.onFailure { log.warn("cup result push failed for ${club.id}: ${it.message}") }
        }
    }
}

data class CupView(
    val state: String,
    val weekStart: LocalDate,
    val drawAt: Instant,
    val roundTimes: List<Instant>,
    val cup: Cup?,
    val ties: List<CupTie>,
    val fixtures: Map<UUID, Fixture>,
    val clubs: Map<UUID, Club>,
    val me: CupEntry?,
    val previous: CupEntry?,
    val division: Int,
)

/** What the cup tab shows: this week's bracket once drawn, otherwise the next draw time and last week's result. */
class CupQueries(private val service: ClubService, private val cups: CupRepository, private val fixtures: FixtureRepository, private val clubs: ClubRepository, private val clock: Clock) {
    suspend fun current(userId: UUID): CupView {
        val club = service.clubOf(userId)
        val now = clock.now()
        val bucket = runCatching { LeagueRules.tzBucket(club.timezone, now) }.getOrDefault(club.tzBucket)   // as the draw buckets it
        val week = Cups.weekStart(now, bucket)
        val entry = cups.entry(week, club.id)
        val cup = entry?.let { cups.find(it.cupId) }
        if (cup == null) {
            // between Thursday 12:00 and the scheduler's draw the club is about to be drawn: show this week's (passed) draw time
            val thisWeek = Cups.drawAt(week, bucket)
            val pending = !now.isBefore(thisWeek) && now.isBefore(Cups.roundAt(week, Cups.ROUNDS, bucket)) && cups.cupsOf(bucket, week).isEmpty()
            val draw = if (pending) thisWeek else Cups.nextDraw(now, bucket)
            val drawWeek = Cups.weekStart(draw, bucket)
            val previous = cups.lastEntryBefore(club.id, week.plusDays(1))?.takeIf { it.outRound != null || cups.find(it.cupId)?.winnerClubId == club.id }
            return CupView("UPCOMING", drawWeek, draw, (1..Cups.ROUNDS).map { Cups.roundAt(drawWeek, it, bucket) }, null, emptyList(), emptyMap(), emptyMap(), null, previous, club.division)
        }
        val ties = cups.ties(cup.id)
        val fx = fixtures.findByIds(ties.map { it.fixtureId }).associateBy { it.id }
        val ids = cups.entries(cup.id).map { it.clubId }
        return CupView(if (cup.status == CupStatus.FINISHED) "FINISHED" else "ACTIVE", cup.weekStart, Cups.drawAt(cup.weekStart, Cups.zoneOf(cup)),
            (1..Cups.ROUNDS).map { Cups.roundAt(cup, it) }, cup, ties, fx, clubs.findByIds(ids).associateBy { it.id }, entry, null, cup.division)
    }
}
