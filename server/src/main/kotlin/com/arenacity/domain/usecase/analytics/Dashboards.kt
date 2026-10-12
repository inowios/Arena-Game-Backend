package com.arenacity.domain.usecase.analytics

import com.arenacity.common.time.Clock
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.AnalyticsEvent
import com.arenacity.domain.repository.AnalyticsRepository
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** One step of the first-ten-minutes funnel: players who reached it, the share of the cohort, the median time from club creation. */
data class FunnelStep(val step: String, val users: Int, val share: Double?, val fromPrevious: Double?, val medianSeconds: Long?, val putOff: Int, val skippedHere: Int)
data class FunnelReport(val from: LocalDate, val to: LocalDate, val cohort: Int, val steps: List<FunnelStep>, val skipped: Int, val finished: Int)

/**
 * The onboarding funnel (GAME-PLAN Е1) over the players whose club was created in the period: club → coach shown →
 * each coach step (tutorial_step with its step name) → done. Events count up to 7 days after the period, so a slow
 * player still finishes inside their cohort. "putOff" = steps passed with "later"; "skippedHere" = the coach was closed on that step.
 */
class FunnelUseCase(private val repo: AnalyticsRepository, private val clock: Clock) {
    companion object {
        /** In the app's order; club_created comes from the server, the rest from the app's coach. */
        val STEPS = listOf("club_created", "tutorial_start", "collect", "build", "squad", "lineup", "play", "reward", "upgrade", "league", "tutorial_done")
        private val NAMES = setOf("club_created", "tutorial_start", "tutorial_step", "tutorial_skip", "tutorial_done")
        private const val HORIZON_DAYS = 7L

        fun median(xs: List<Long>): Long? = if (xs.isEmpty()) null else xs.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }

        /** The step an event stands for (null for events that are not a funnel step). */
        fun stepOf(e: AnalyticsEvent): String? = when (e.name) {
            "tutorial_step" -> e.props["step"]
            "club_created", "tutorial_start", "tutorial_done" -> e.name
            else -> null
        }

        fun build(events: List<AnalyticsEvent>, from: LocalDate, to: LocalDate): FunnelReport {
            val byUser = events.filter { it.userId != null }.groupBy { it.userId!! }
            val created = byUser.mapNotNull { (u, es) -> es.firstOrNull { it.name == "club_created" }?.let { u to it.at } }.toMap()
            val reached = mutableMapOf<String, MutableMap<UUID, Instant>>()
            val putOff = mutableMapOf<String, Int>(); val skippedAt = mutableMapOf<String, Int>()
            for ((u, es) in byUser) {
                if (u !in created) continue
                for (e in es) {
                    if (e.name == "tutorial_skip") { val s = e.props["step"] ?: "?"; skippedAt[s] = (skippedAt[s] ?: 0) + 1; continue }
                    val s = stepOf(e) ?: continue
                    reached.getOrPut(s) { mutableMapOf() }.putIfAbsent(u, e.at)
                    if (e.props["later"] == "1") putOff[s] = (putOff[s] ?: 0) + 1
                }
            }
            val cohort = created.size
            var previous: Int? = null
            val steps = STEPS.map { s ->
                val hits = reached[s].orEmpty()
                val secs = hits.mapNotNull { (u, at) -> created[u]?.let { Duration.between(it, at).seconds.coerceAtLeast(0) } }
                val row = FunnelStep(s, hits.size, Analytics.rate(hits.size, cohort), previous?.let { Analytics.rate(hits.size, it) },
                    if (s == "club_created") 0 else median(secs), putOff[s] ?: 0, skippedAt[s] ?: 0)
                previous = hits.size
                row
            }
            return FunnelReport(from, to, cohort, steps, skippedAt.values.sum(), reached["tutorial_done"]?.size ?: 0)
        }
    }

    suspend operator fun invoke(days: Int = 14, to: LocalDate? = null): FunnelReport {
        if (days !in 1..120) throw ValidationException("days must be 1..120")
        val today = clock.now().atZone(ZoneOffset.UTC).toLocalDate()
        val end = to?.coerceAtMost(today) ?: today
        val start = end.minusDays(days - 1L)
        val from = start.atStartOfDay(ZoneOffset.UTC).toInstant(); val until = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val events = repo.cohortEvents(from, until, until.plusSeconds(HORIZON_DAYS * 86_400), NAMES)
        return build(events, start, end)
    }
}

/** Spread of one number over the active clubs. */
data class Spread(val p10: Long, val p50: Long, val p90: Long, val max: Long)
data class LiveBalance(val kickoffs: Int, val finishes: Int, val finishRate: Double?, val outcomes: Map<String, Int>, val controls: Map<String, Int>, val vsBots: Int)
data class BalanceReport(
    val from: LocalDate, val to: LocalDate,
    val live: LiveBalance,
    val obstaclesCleared: Int, val obstacleGold: Long,
    val purchases: Map<String, Int>, val purchaseStarts: Map<String, Int>, val adRewards: Int,
    val stadiumUps: Map<Int, Int>,
    val activeClubs: Int, val coins: Spread?, val gold: Spread?, val trophies: Spread?, val clubLevel: Spread?, val stadium: Map<Int, Int>,
    val buildings: Map<String, Map<Int, Int>>,
)

/**
 * The balance dashboard (GAME-PLAN Е2): arena matches (finish rate, results, controls), obstacles, purchases and
 * their starts, stadium level-ups, and the economy of the clubs active in the period — to hold against economy.py
 * after the closed test.
 */
class BalanceUseCase(private val repo: AnalyticsRepository, private val clock: Clock) {
    companion object {
        fun spread(xs: List<Long>): Spread? {
            if (xs.isEmpty()) return null
            val s = xs.sorted()
            fun p(q: Double) = s[((s.size - 1) * q).toInt()]
            return Spread(p(0.1), p(0.5), p(0.9), s.last())
        }
        private fun count(props: List<Map<String, String>>, key: String) = props.groupingBy { it[key] ?: "?" }.eachCount().toSortedMap()
    }

    suspend operator fun invoke(days: Int = 7, to: LocalDate? = null): BalanceReport {
        if (days !in 1..90) throw ValidationException("days must be 1..90")
        val today = clock.now().atZone(ZoneOffset.UTC).toLocalDate()
        val end = to?.coerceAtMost(today) ?: today
        val start = end.minusDays(days - 1L)
        val from = start.atStartOfDay(ZoneOffset.UTC).toInstant(); val until = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val kick = repo.eventProps("live_kickoff", from, until)
        val fin = repo.eventProps("live_finish", from, until)
        val obstacles = repo.eventProps("obstacle_cleared", from, until)
        val starts = repo.eventProps("purchase_start", from, until)
        val ups = repo.eventProps("stadium_level", from, until)
        val clubs = repo.activeClubs(start, end)
        return BalanceReport(
            from = start, to = end,
            live = LiveBalance(kick.size, fin.size, Analytics.rate(fin.size, kick.size), count(fin, "outcome"), count(kick, "control"), kick.count { it["bot"] == "1" }),
            obstaclesCleared = obstacles.size, obstacleGold = obstacles.sumOf { it["gold"]?.toLongOrNull() ?: 0L },
            purchases = repo.purchases(from, until), purchaseStarts = count(starts, "product"), adRewards = repo.adRewards(from, until),
            stadiumUps = ups.mapNotNull { it["level"]?.toIntOrNull() }.groupingBy { it }.eachCount().toSortedMap(),
            activeClubs = clubs.size,
            coins = spread(clubs.map { it.coins }), gold = spread(clubs.map { it.gold.toLong() }), trophies = spread(clubs.map { it.trophies.toLong() }),
            clubLevel = spread(clubs.map { it.level.toLong() }), stadium = clubs.groupingBy { it.stadium }.eachCount().toSortedMap(),
            buildings = repo.buildingLevels().toSortedMap(),
        )
    }
}
