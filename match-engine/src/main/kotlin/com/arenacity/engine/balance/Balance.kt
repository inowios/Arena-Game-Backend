package com.arenacity.engine.balance

import com.arenacity.engine.model.*
import com.arenacity.engine.sim.MatchSimulator
import com.arenacity.engine.sim.Rng

data class Stats(
    val matches: Int, val meanGoals: Double, val drawShare: Double, val homeWinShare: Double, val awayWinShare: Double,
    val highScoring: Double, val meanShotsHome: Double, val meanShotsAway: Double, val overtimeShare: Double = 0.0, val decidingSetShare: Double = 0.0,
)

object Balance {
    fun shape(sport: SportProfile): List<Pair<Position, Int>> = when (sport.id) {
        "basketball" -> listOf(Position.G to 2, Position.F to 2, Position.C to 1)
        "tennis" -> listOf(Position.S to 1)
        else -> listOf(Position.GK to 1, Position.DEF to 4, Position.MID to 4, Position.FWD to 2)
    }

    fun benchShape(sport: SportProfile): List<Position> = when (sport.id) {
        "basketball" -> listOf(Position.G, Position.F, Position.C, Position.G, Position.F)
        "tennis" -> listOf(Position.S)
        else -> listOf(Position.GK, Position.DEF, Position.MID, Position.MID, Position.FWD)
    }

    fun squad(rating: Int, team: String, seed: Long, sport: SportProfile = FOOTBALL): Lineup {
        val rng = Rng(seed)
        fun v() = (rating + (rng.next() * 12 - 6)).toInt().coerceIn(1, 99)
        val starters = shape(sport).flatMap { (p, n) -> (1..n).map { Athlete("$team-$p-$it", "$team $p $it", p, Attributes(v(), v(), v(), v(), v())) } }
        val bench = benchShape(sport).mapIndexed { i, p -> Athlete("$team-b$i", "$team bench $i", p, Attributes(v(), v(), v(), v(), v())) }
        return Lineup(team, starters, bench)
    }

    fun run(homeRating: Int, awayRating: Int, n: Int, sport: SportProfile = FOOTBALL, tactics: Tactics = Tactics()): Stats {
        var goals = 0L; var draws = 0; var hw = 0; var aw = 0; var high = 0; var sh = 0L; var sa = 0L; var ot = 0; var deciding = 0
        val highLine = when (sport.id) { "basketball" -> 220; "tennis" -> Int.MAX_VALUE; else -> 7 }
        for (i in 1..n) {
            val r = MatchSimulator.simulate(squad(homeRating, "H", i.toLong(), sport).copy(tactics = tactics), squad(awayRating, "A", 100_000L + i, sport), sport, i.toLong())
            val t = r.score.home + r.score.away
            goals += t; sh += r.homeShots; sa += r.awayShots
            if (t >= highLine) high++
            if (r.overtime) ot++
            if (sport.mode == SimMode.SETS && r.periods.size == sport.setsToWin * 2 - 1) deciding++
            when { r.score.home > r.score.away -> hw++; r.score.home < r.score.away -> aw++; else -> draws++ }
        }
        return Stats(n, goals.toDouble() / n, draws.toDouble() / n, hw.toDouble() / n, aw.toDouble() / n, high.toDouble() / n, sh.toDouble() / n, sa.toDouble() / n, ot.toDouble() / n, deciding.toDouble() / n)
    }
}

fun main(args: Array<String>) {
    val n = args.firstOrNull()?.toIntOrNull() ?: 3000
    println("scenario                        | score  | draw  | homeW | awayW | high  | shotsH | shotsA | OT/3rd set")
    for ((label, s) in listOf(
        "football 70 vs 70" to Balance.run(70, 70, n),
        "football 80 vs 60" to Balance.run(80, 60, n),
        "football 60 vs 80" to Balance.run(60, 80, n),
        "football 70 vs 70 ATT/HIGH" to Balance.run(70, 70, n, tactics = Tactics(Style.ATTACK, Tempo.HIGH)),
        "football 70 vs 70 DEF/COUNTER" to Balance.run(70, 70, n, tactics = Tactics(Style.DEFEND, Tempo.LOW, Plan.COUNTER)),
        "basketball 70 vs 70" to Balance.run(70, 70, n, BASKETBALL),
        "basketball 80 vs 60" to Balance.run(80, 60, n, BASKETBALL),
        "basketball 60 vs 80" to Balance.run(60, 80, n, BASKETBALL),
        "basketball 75 vs 70" to Balance.run(75, 70, n, BASKETBALL),
        "tennis 70 vs 70" to Balance.run(70, 70, n, TENNIS),
        "tennis 75 vs 70" to Balance.run(75, 70, n, TENNIS),
        "tennis 80 vs 60" to Balance.run(80, 60, n, TENNIS),
        "tennis 60 vs 80" to Balance.run(60, 80, n, TENNIS),
    )) {
        println("%-31s | %6.2f | %.2f  | %.2f  | %.2f  | %.3f | %6.1f | %6.1f | %.2f / %.2f".format(label, s.meanGoals, s.drawShare, s.homeWinShare, s.awayWinShare, s.highScoring, s.meanShotsHome, s.meanShotsAway, s.overtimeShare, s.decidingSetShare))
    }
}
