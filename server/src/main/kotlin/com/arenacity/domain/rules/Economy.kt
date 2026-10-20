package com.arenacity.domain.rules

import java.time.LocalDate
import java.util.UUID

/** The daily tasks (the client's texts are keyed by the name: task.TRAIN_1 …). The Arena ones are fed by POST /club/arena/result. */
enum class TaskKind(val target: Int, val xp: Int, val title: String) {
    /** «Потренувати героя»: start one hero training session (POST /club/heroes/{id}/train). */
    TRAIN_1(1, 40, "Train a hero"),
    PLAY_FRIENDLY(1, 30, "Play a friendly"),
    WIN_MATCH(1, 60, "Win your matchday match"),
    WIN_SECTIONS(2, 70, "Win 2 sections on matchday"),
    /** Save the Arena triple (PUT /club/heroes/lineup). */
    SET_LINEUP(1, 20, "Save your lineup"),
    // the Arena: one of these is on the list every day
    PLAY_ARENA_3(3, 50, "Play 3 Arena matches"),
    WIN_ARENA_2(2, 70, "Win 2 Arena matches"),
    /** «Вивести 5 суперників»: knock out five rival heroes. */
    KO_5(5, 60, "Knock out 5 rival heroes in the Arena"),
    /** Use an alternate skill (Mirai's «p004.a0» for now). */
    ALT_SKILL_1(1, 50, "Use an alternate skill");

    val arena get() = this == PLAY_ARENA_3 || this == WIN_ARENA_2 || this == KO_5 || this == ALT_SKILL_1
}

/** Gameplay events reported by other modules: they advance daily tasks and achievement counters. */
enum class ProgressEvent(val task: TaskKind?, val metric: Metric? = null) {
    /** A hero training session started (usecase.hero.HeroUseCases.train). */
    TRAINING_STARTED(TaskKind.TRAIN_1, Metric.TRAININGS),
    FRIENDLY_PLAYED(TaskKind.PLAY_FRIENDLY, Metric.FRIENDLIES), MATCH_WON(TaskKind.WIN_MATCH, Metric.MATCHES_WON),
    SECTION_WON(TaskKind.WIN_SECTIONS, Metric.SECTIONS_WON), LINEUP_SAVED(TaskKind.SET_LINEUP), MATCH_PLAYED(null),
    ACADEMY_GRADUATE(null, Metric.ACADEMY_GRADUATES), INVITE_JOINED(null, Metric.INVITES),
    // the Arena match result (usecase.arena.ArenaMatchUseCases.report)
    ARENA_PLAYED(TaskKind.PLAY_ARENA_3), ARENA_WON(TaskKind.WIN_ARENA_2), KNOCKOUT(TaskKind.KO_5, Metric.KNOCKOUTS), ALT_SKILL_USED(TaskKind.ALT_SKILL_1, Metric.ALT_SKILLS),
}

data class Reward(val coins: Long = 0, val gold: Int = 0) { operator fun plus(o: Reward) = Reward(coins + o.coins, gold + o.gold) }

enum class Store { GOOGLE, APPLE, DEV }
/** Real-money store product (ids must match Play Console / App Store Connect). */
data class Product(val id: String, val gold: Int = 0, val coins: Long = 0, val premiumPass: Boolean = false, val oneTime: Boolean = false, val item: String? = null)
/** Coins bought with gold (spec §6 "пакеты монет" are sold for gold). */
data class CoinPack(val id: String, val gold: Int, val coins: Long)

object Economy {
    val LOGIN_CALENDAR = listOf(Reward(coins = 150), Reward(coins = 200), Reward(coins = 300), Reward(gold = 2), Reward(coins = 400), Reward(gold = 3), Reward(coins = 800, gold = 5))
    const val PASS_LEVELS = 30
    const val XP_PER_LEVEL = 100
    const val TASKS_PER_DAY = 3
    /** Arena matches that pay coins a UTC day (POST /club/arena/result); after them a match still gives trophies and experience. */
    const val ARENA_REWARDED_PER_DAY = 10
    /** Gold to finish a timer now (a training, the hospital's rest): 1 per started 10 minutes, at least 1. */
    fun speedUpGold(remainingSeconds: Long): Int = if (remainingSeconds <= 0) 0 else maxOf(1, kotlin.math.ceil(remainingSeconds / 600.0).toInt())

    val PRODUCTS = listOf(
        Product("gold_small", gold = 100), Product("gold_medium", gold = 550), Product("gold_large", gold = 1200), Product("gold_huge", gold = 2600),
        Product("season_pass", premiumPass = true),
        // once per club, at a big discount: gold, coins and a fountain for the plaza
        Product("starter_pack", gold = 500, coins = 20_000, oneTime = true),
    ).associateBy { it.id }

    val COIN_PACKS = listOf(CoinPack("coins_small", 25, 3_000), CoinPack("coins_medium", 100, 13_000), CoinPack("coins_large", 400, 60_000)).associateBy { it.id }

    /** Premium track's last level grants an elite athlete (spec §6). */
    const val ELITE_LEVEL = 30
    const val ELITE_QUALITY_MIN = 70
    const val ELITE_QUALITY_MAX = 76
    /** Cosmetic on a pass level: [premium] track or free track. */
    fun passItem(level: Int, premium: Boolean): Cosmetic? = Cosmetics.ALL.firstOrNull { it.source == CosmeticSource.PASS && it.unlock == (if (premium) "premium:" else "free:") + level }

    /** Login calendar: next day index (1..7) given the last claim date; a skipped day restarts at 1. */
    fun nextLoginDay(lastClaim: LocalDate?, currentDay: Int, today: LocalDate): Int = when {
        lastClaim == null -> 1
        lastClaim == today -> currentDay
        lastClaim.plusDays(1) == today -> if (currentDay >= LOGIN_CALENDAR.size) 1 else currentDay + 1
        else -> 1
    }

    fun loginReward(day: Int) = LOGIN_CALENDAR[(day - 1).coerceIn(0, LOGIN_CALENDAR.size - 1)]

    /** Deterministic 3 tasks per club per day. WIN_MATCH only on days a league match is scheduled (caller filters). */
    fun tasksFor(clubId: UUID, day: LocalDate, includeMatch: Boolean, multiSection: Boolean = false): List<TaskKind> {
        val arena = TaskKind.values().filter { it.arena }
        val pool = TaskKind.values().filter { !it.arena && (includeMatch || (it != TaskKind.WIN_MATCH && it != TaskKind.WIN_SECTIONS)) && (multiSection || it != TaskKind.WIN_SECTIONS) }
        val seed = Math.floorMod(clubId.hashCode() + day.toEpochDay().toInt() * 31, pool.size * arena.size)
        val rest = (0 until TASKS_PER_DAY - 1).map { pool[(seed + it * 2) % pool.size] }.distinct().let { if (it.size < TASKS_PER_DAY - 1) (it + pool).distinct().take(TASKS_PER_DAY - 1) else it }
        return listOf(arena[seed % arena.size]) + rest
    }

    fun passLevel(xp: Int) = (xp / XP_PER_LEVEL).coerceIn(0, PASS_LEVELS)

    /** Free track: coins ramping up, gold every 5 levels. Premium: double coins, +gold, cosmetics flagged by level 30. */
    fun freeReward(level: Int) = if (level % 5 == 0) Reward(gold = 3 + level / 10) else Reward(coins = 100L * level)
    fun premiumReward(level: Int) = if (level % 5 == 0) Reward(coins = 300L * level, gold = 8 + level / 5) else Reward(coins = 250L * level)
}
