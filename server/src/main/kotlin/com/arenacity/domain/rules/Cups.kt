package com.arenacity.domain.rules

import com.arenacity.domain.model.Fixture
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/**
 * Weekly knockout cup per timezone bucket (spec post-release: cups). Every club active in the last [ACTIVE_DAYS] days
 * is entered automatically; entrants are grouped by strength into 16-club cups (bots fill the last one). Draw on
 * Thursday 12:00, round of 16 Thursday 18:00, quarter-finals Friday, semi-finals Saturday, final Sunday — two hours
 * before the 20:00 league matchday, bucket time. A tie is an Arena match of the clubs' hero triples (HeroMatch): win or
 * loss, never level. Prizes by the round reached.
 */
object Cups {
    const val SIZE = 16
    const val ROUNDS = 4
    const val ACTIVE_DAYS = 7L
    const val DRAW_HOUR = 12
    const val ROUND_HOUR = 18

    enum class Stage { ROUND_OF_16, QUARTER_FINAL, SEMI_FINAL, RUNNER_UP, CHAMPION }

    fun zone(bucket: Int): ZoneOffset = ZoneOffset.ofHours(bucket.coerceIn(-18, 18))
    /** The cup's own zone when it has one (rounds at 18:00 local, DST included), else the bucket offset. */
    fun zoneOf(cup: com.arenacity.domain.model.Cup): ZoneId = cup.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone(cup.tzBucket)
    /** Monday of the bucket-local week containing [now]. */
    fun weekStart(now: Instant, bucket: Int): LocalDate = now.atZone(zone(bucket)).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun drawAt(weekStart: LocalDate, bucket: Int): Instant = drawAt(weekStart, zone(bucket))
    fun drawAt(weekStart: LocalDate, zone: ZoneId): Instant = weekStart.plusDays(3).atTime(LocalTime.of(DRAW_HOUR, 0)).atZone(zone).toInstant()
    /** Round 1 Thursday … round 4 (final) Sunday, 18:00 bucket time. */
    fun roundAt(weekStart: LocalDate, round: Int, bucket: Int): Instant = roundAt(weekStart, round, zone(bucket))
    fun roundAt(weekStart: LocalDate, round: Int, zone: ZoneId): Instant = weekStart.plusDays(2L + round).atTime(LocalTime.of(ROUND_HOUR, 0)).atZone(zone).toInstant()
    fun roundAt(cup: com.arenacity.domain.model.Cup, round: Int): Instant = roundAt(cup.weekStart, round, zoneOf(cup))
    /** The draw of this week when it is still ahead, else next week's. */
    fun nextDraw(now: Instant, bucket: Int): Instant {
        val week = weekStart(now, bucket)
        val d = drawAt(week, bucket)
        return if (now.isBefore(d)) d else drawAt(week.plusDays(7), bucket)
    }

    /** Stage reached by a club that lost in [outRound] (null = never lost: champion). */
    fun stage(outRound: Int?): Stage = when (outRound) { null -> Stage.CHAMPION; 1 -> Stage.ROUND_OF_16; 2 -> Stage.QUARTER_FINAL; 3 -> Stage.SEMI_FINAL; else -> Stage.RUNNER_UP }

    private val coins = listOf(300L, 600, 1_200, 2_500, 5_000)
    private val gold = listOf(0, 0, 3, 8, 20)
    private val reputation = listOf(5, 10, 20, 35, 60)
    fun prize(stage: Stage, division: Int): Divisions.Prize =
        Divisions.Prize((coins[stage.ordinal] * Divisions.multiplier(division)).toLong(), gold[stage.ordinal], reputation[stage.ordinal])

    /** Chance to score one penalty: shooters' technique and nerve against the other side's. */
    /** Winner of a played cup fixture: the points (an Arena match has no draws), else the score (legacy rows). */
    fun winner(f: Fixture): UUID {
        val hp = f.homePoints ?: 0; val ap = f.awayPoints ?: 0
        if (hp != ap) return if (hp > ap) f.homeClubId else f.awayClubId
        return if ((f.homeScore ?: 0) >= (f.awayScore ?: 0)) f.homeClubId else f.awayClubId
    }

    fun seed(cupId: UUID, round: Int, slot: Int) = cupId.leastSignificantBits xor (round * 1_000_003L + slot * 7_919L) xor cupId.mostSignificantBits.rotateLeft(round)
}
