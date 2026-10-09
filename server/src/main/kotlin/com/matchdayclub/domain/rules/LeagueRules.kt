package com.matchdayclub.domain.rules

import com.matchdayclub.domain.model.Fixture
import com.matchdayclub.domain.model.FixtureStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random

object LeagueRules {
    const val PUBLIC_SIZE = 8
    const val PRIVATE_MAX = 20
    const val ROUNDS = 28
    const val MATCHDAY_HOUR = 20
    const val FRIENDLIES_PER_DAY = 3
    const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** UTC offset in whole hours for grouping clubs that share a matchday hour. */
    fun tzBucket(timezone: String, now: Instant): Int = (ZoneId.of(timezone).rules.getOffset(now).totalSeconds / 3600.0).roundToInt()

    /** First matchday is today if it is before (hour - 1) local time, otherwise tomorrow. */
    fun firstSeasonStart(timezone: String, now: Instant, hour: Int = MATCHDAY_HOUR): LocalDate {
        val local = now.atZone(ZoneId.of(timezone))
        return if (local.hour < hour - 1) local.toLocalDate() else local.toLocalDate().plusDays(1)
    }

    fun code(seed: Long): String { val r = Random(seed); return (1..6).map { CODE_ALPHABET[r.nextInt(CODE_ALPHABET.length)] }.joinToString("") }
}

object Schedule {
    /** Berger round-robin for an even number of clubs, repeated so every pair meets `cycles` times with alternating venues. */
    fun rounds(clubIds: List<UUID>, seed: Long, cycles: Int = 4): List<List<Pair<UUID, UUID>>> {
        require(clubIds.size % 2 == 0 && clubIds.size >= 2) { "even number of clubs required" }
        val ids = clubIds.shuffled(Random(seed))
        val n = ids.size
        val base = mutableListOf<List<Pair<UUID, UUID>>>()
        val rot = ids.drop(1).toMutableList()
        repeat(n - 1) { r ->
            val order = listOf(ids[0]) + rot
            val pairs = (0 until n / 2).map { i -> val a = order[i]; val b = order[n - 1 - i]; if ((r + i) % 2 == 0) a to b else b to a }
            base += pairs
            rot.add(0, rot.removeAt(rot.size - 1))
        }
        return (0 until cycles).flatMap { c -> base.map { round -> if (c % 2 == 0) round else round.map { (h, a) -> a to h } } }
    }

    fun roundInstant(seasonStart: LocalDate, round: Int, timezone: String, hour: Int): Instant =
        seasonStart.plusDays((round - 1).toLong()).atTime(LocalTime.of(hour, 0)).atZone(ZoneId.of(timezone)).toInstant()

    /** The last round's kick-off time has passed (all rounds are played by the runner before it closes the season). */
    fun seasonOver(l: com.matchdayclub.domain.model.League, now: Instant) = !roundInstant(l.seasonStart, LeagueRules.ROUNDS, l.timezone, l.matchdayHour).isAfter(now)
}

enum class Outcome { WIN, DRAW, LOSS }

/**
 * One club's row. [points] = sum of section points (3 win / 1 draw / 0 loss per section, so multi-sport clubs can earn up to
 * 9 a matchday); won/drawn/lost compare the fixture's points; gf/ga are football goals (tiebreak after points).
 */
data class StandingRow(val clubId: UUID, val played: Int = 0, val won: Int = 0, val drawn: Int = 0, val lost: Int = 0, val gf: Int = 0, val ga: Int = 0, val points: Int = 0) {
    val gd get() = gf - ga
}

object Standings {
    fun table(fixtures: List<Fixture>, memberIds: Collection<UUID>): List<StandingRow> {
        val rows = memberIds.associateWith { StandingRow(it) }.toMutableMap()
        for (f in fixtures.filter { it.status == FixtureStatus.PLAYED }) {
            val hs = f.homeScore ?: continue; val `as` = f.awayScore ?: continue
            val hp = f.homePoints ?: Sections.points(hs, `as`); val ap = f.awayPoints ?: Sections.points(`as`, hs)
            rows[f.homeClubId]?.let { rows[f.homeClubId] = it.add(hs, `as`, hp, ap) }
            rows[f.awayClubId]?.let { rows[f.awayClubId] = it.add(`as`, hs, ap, hp) }
        }
        return rows.values.sortedWith(compareByDescending<StandingRow> { it.points }.thenByDescending { it.won }.thenByDescending { it.gd }.thenByDescending { it.gf }.thenBy { it.clubId })
    }

    private fun StandingRow.add(gf: Int, ga: Int, pf: Int, pa: Int) = copy(
        played = played + 1, won = won + if (pf > pa) 1 else 0, drawn = drawn + if (pf == pa) 1 else 0, lost = lost + if (pf < pa) 1 else 0,
        gf = this.gf + gf, ga = this.ga + ga, points = points + pf,
    )

    fun outcome(forScore: Int, againstScore: Int) = when { forScore > againstScore -> Outcome.WIN; forScore == againstScore -> Outcome.DRAW; else -> Outcome.LOSS }
    fun outcomeFromPoints(sectionPoints: Int) = when (sectionPoints) { 3 -> Outcome.WIN; 1 -> Outcome.DRAW; else -> Outcome.LOSS }
    fun reputationDelta(o: Outcome) = when (o) { Outcome.WIN -> 12; Outcome.DRAW -> 5; Outcome.LOSS -> 1 }
    fun coinsReward(o: Outcome): Long = when (o) { Outcome.WIN -> 300; Outcome.DRAW -> 150; Outcome.LOSS -> 60 }
    const val FRIENDLY_COINS = 40L
    const val MATCH_FATIGUE = 10
    const val FRIENDLY_FATIGUE = 8
    const val INJURY_DAYS = 2L
}
