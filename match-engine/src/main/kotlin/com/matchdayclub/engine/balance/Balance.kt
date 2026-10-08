package com.matchdayclub.engine.balance

import com.matchdayclub.engine.model.*
import com.matchdayclub.engine.sim.MatchSimulator
import com.matchdayclub.engine.sim.Rng

data class Stats(
    val matches: Int, val meanGoals: Double, val drawShare: Double, val homeWinShare: Double, val awayWinShare: Double,
    val highScoring: Double, val meanShotsHome: Double, val meanShotsAway: Double,
)

object Balance {
    private val shape = listOf(Position.GK to 1, Position.DEF to 4, Position.MID to 4, Position.FWD to 2)

    fun squad(rating: Int, team: String, seed: Long): Lineup {
        val rng = Rng(seed)
        fun v() = (rating + (rng.next() * 12 - 6)).toInt().coerceIn(1, 99)
        val starters = shape.flatMap { (p, n) -> (1..n).map { Athlete("$team-$p-$it", "$team $p $it", p, Attributes(v(), v(), v(), v(), v())) } }
        val bench = listOf(Position.GK, Position.DEF, Position.MID, Position.MID, Position.FWD).mapIndexed { i, p -> Athlete("$team-b$i", "$team bench $i", p, Attributes(v(), v(), v(), v(), v())) }
        return Lineup(team, starters, bench)
    }

    fun run(homeRating: Int, awayRating: Int, n: Int, sport: SportProfile = FOOTBALL, tactics: Tactics = Tactics()): Stats {
        var goals = 0; var draws = 0; var hw = 0; var aw = 0; var high = 0; var sh = 0; var sa = 0
        for (i in 1..n) {
            val r = MatchSimulator.simulate(squad(homeRating, "H", i.toLong()).copy(tactics = tactics), squad(awayRating, "A", 100_000L + i), sport, i.toLong())
            val t = r.score.home + r.score.away
            goals += t; sh += r.homeShots; sa += r.awayShots
            if (t >= 7) high++
            when { r.score.home > r.score.away -> hw++; r.score.home < r.score.away -> aw++; else -> draws++ }
        }
        return Stats(n, goals.toDouble() / n, draws.toDouble() / n, hw.toDouble() / n, aw.toDouble() / n, high.toDouble() / n, sh.toDouble() / n, sa.toDouble() / n)
    }
}

fun main(args: Array<String>) {
    val n = args.firstOrNull()?.toIntOrNull() ?: 3000
    println("scenario             | goals | draw  | homeW | awayW | 7+    | shotsH | shotsA")
    for ((label, s) in listOf(
        "70 vs 70" to Balance.run(70, 70, n),
        "80 vs 60" to Balance.run(80, 60, n),
        "60 vs 80" to Balance.run(60, 80, n),
        "70 vs 70 ATT/HIGH" to Balance.run(70, 70, n, tactics = Tactics(Style.ATTACK, Tempo.HIGH)),
        "70 vs 70 DEF/COUNTER" to Balance.run(70, 70, n, tactics = Tactics(Style.DEFEND, Tempo.LOW, Plan.COUNTER)),
    )) {
        println("%-20s | %.2f  | %.2f  | %.2f  | %.2f  | %.3f | %.1f   | %.1f".format(label, s.meanGoals, s.drawShare, s.homeWinShare, s.awayWinShare, s.highScoring, s.meanShotsHome, s.meanShotsAway))
    }
}
