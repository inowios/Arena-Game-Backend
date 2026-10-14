package com.arenacity.domain.rules

import kotlin.random.Random

/** What lies around an unkept plot. Footprint in cells, cost (× the stadium factor), clearing time and gold multiplier. */
enum class ObstacleKind(val width: Int, val height: Int, val baseCost: Long, val seconds: Long, val goldFactor: Double, val weight: Int) {
    BUSH(1, 1, 200, 5 * 60L, 1.0, 34),
    STUMP(1, 1, 400, 10 * 60L, 1.0, 24),
    RUBBISH(1, 1, 600, 15 * 60L, 1.2, 20),
    ROCK(1, 1, 1_000, 30 * 60L, 1.5, 16),
    SHED(2, 2, 2_500, 60 * 60L, 2.5, 6),
}

/**
 * Obstacles (like Clash of Clans' trees and rocks): a fresh club starts with a few, then one more turns up every
 * [SPAWN_SECONDS] while there are fewer than [MAX] and there is a free spot. Clearing one takes a builder, costs coins
 * (more at a bigger stadium) and pays its gold, decided when it appeared.
 */
object Obstacles {
    /**
     * Whether obstacles turn up at all: off since the owner's 14 October (pass 3: «прибери камни и т.д., которые случайно
     * появляются — уберём пока этот функционал»); V40 took away every club's, the clients hide them. The rules stay for later.
     */
    const val ENABLED = false
    const val MAX = 10
    const val START = 6
    const val SPAWN_SECONDS = 6 * 3600L

    fun cost(kind: ObstacleKind, stadium: Int): Long = (kind.baseCost * (1.0 + 0.6 * (stadium.coerceIn(1, Stadium.MAX_LEVEL) - 1))).toLong() / 10 * 10

    fun pick(r: Random): ObstacleKind {
        var x = r.nextInt(ObstacleKind.values().sumOf { it.weight })
        for (k in ObstacleKind.values()) { x -= k.weight; if (x < 0) return k }
        return ObstacleKind.BUSH
    }

    /** Gold inside: mostly 1–3, sometimes 5 or 8, scaled by the kind. */
    fun gold(kind: ObstacleKind, r: Random): Int {
        val roll = r.nextInt(100)
        val base = when { roll < 40 -> 1; roll < 70 -> 2; roll < 85 -> 3; roll < 95 -> 5; else -> 8 }
        return maxOf(1, Math.round(base * kind.goldFactor).toInt())
    }

    /** How many turn up between [last] and [now] (seconds), never above room for [MAX]. */
    fun due(lastEpoch: Long, nowEpoch: Long, have: Int): Int = if (have >= MAX) 0 else minOf(((nowEpoch - lastEpoch) / SPAWN_SECONDS).toInt(), MAX - have)

    fun footprint(kind: ObstacleKind, x: Int, y: Int) = Footprint(x, y, kind.width, kind.height)
}
