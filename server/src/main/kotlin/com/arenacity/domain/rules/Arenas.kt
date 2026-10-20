package com.arenacity.domain.rules

/** One rung of the Arena ladder: trophies needed, coin multiplier, the division whose bots stand here. */
data class Arena(val index: Int, val key: String, val trophies: Int, val rewardK: Double, val botDivision: Int)

/**
 * Arenas and trophies of the Arena matches (the Clash-style ladder): a win moves you up, a loss down, the arena sets
 * the weekly chest (usecase.arena.ArenaChestUseCase) and the trophy road (rules.Roads). Separate from the league divisions.
 */
object Arenas {
    /** Bot "division" of the legends arena: live-match only, never a league division. */
    const val LEGEND_DIVISION = 8
    val ALL = listOf(
        Arena(0, "YARD", 0, 1.0, 1), Arena(1, "DISTRICT", 300, 1.3, 2), Arena(2, "CITY", 700, 1.6, 3), Arena(3, "REGION", 1300, 2.0, 4),
        Arena(4, "COUNTRY", 2200, 2.5, 5), Arena(5, "CONTINENT", 3500, 3.1, 6), Arena(6, "WORLD", 5500, 3.8, 7),
        // above the world: legends — bots up to 99, for squads a stadium 9–10 lets grow that far
        Arena(7, "LEGENDS", 8000, 4.5, LEGEND_DIVISION),
    )
    fun of(trophies: Int): Arena = ALL.last { trophies >= it.trophies }
    fun byKey(key: String) = ALL.firstOrNull { it.key == key } ?: ALL[0]

    /** Trophy change for the attacker: bigger for beating a stronger side, smaller for beating a weaker one; losses the other way round. */
    fun delta(outcome: Outcome, myOverall: Int, oppOverall: Int): Int {
        val edge = (oppOverall - myOverall).coerceIn(-10, 10)
        return when (outcome) {
            Outcome.WIN -> 25 + edge
            Outcome.DRAW -> if (edge > 0) 4 else 0
            Outcome.LOSS -> -(18 - edge).coerceAtLeast(8)
        }
    }

    /** The weekly chest of the arena you stand in: coins and gold scale with the rung. */
    fun chestCoins(arena: Arena): Long = (400 * arena.rewardK).toLong()
    fun chestGold(arena: Arena): Int = 3 + arena.index * 2
    fun isoWeek(now: java.time.Instant, zone: java.time.ZoneId): Int { val d = now.atZone(zone).toLocalDate(); val wf = java.time.temporal.WeekFields.ISO; return d.get(wf.weekBasedYear()) * 100 + d.get(wf.weekOfWeekBasedYear()) }
    /** Start of the next ISO week in the club's zone. */
    fun nextWeek(now: java.time.Instant, zone: java.time.ZoneId): java.time.Instant { val d = now.atZone(zone).toLocalDate(); val monday = d.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)); return monday.atStartOfDay(zone).toInstant() }
}
