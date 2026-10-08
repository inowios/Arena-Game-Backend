package com.matchdayclub.engine.sim

import com.matchdayclub.engine.model.*

object MatchSimulator {
    private class Side(val lineup: Lineup, val isHome: Boolean) {
        val starters = lineup.starters.toMutableList()
        val bench = lineup.bench.toMutableList()
        var subs = 0
        var possessions = 0
        var shots = 0
        var goals = 0
        fun strength(sport: SportProfile) = Strength.of(lineup.copy(starters = starters), sport, isHome)
    }

    fun simulate(home: Lineup, away: Lineup, sport: SportProfile = FOOTBALL, seed: Long): MatchResult {
        val rng = Rng(seed)
        val h = Side(home, true)
        val a = Side(away, false)
        var hs = h.strength(sport)
        var aws = a.strength(sport)
        val events = mutableListOf(MatchEvent(0, EventType.KICKOFF, home.teamId))
        var halftime = false

        for (i in 0 until sport.possessions) {
            val minute = (i * sport.durationMinutes) / sport.possessions
            if (!halftime && minute >= sport.durationMinutes / 2) {
                events += MatchEvent(sport.durationMinutes / 2, EventType.HALFTIME, home.teamId)
                halftime = true
            }
            if (i > 0 && i % 20 == 0) { hs = h.strength(sport); aws = a.strength(sport) }

            val homeBall = Possession.homeWins(hs, aws, rng)
            val att = if (homeBall) h else a
            val def = if (homeBall) a else h
            val attS = if (homeBall) hs else aws
            val defS = if (homeBall) aws else hs
            att.possessions++
            fatigue(att, attS.tempoCost * 0.15)
            injuries(att, minute, rng, sport, events)

            when (val o = Possession.attack(attS, defS, att.starters, sport, rng)) {
                PossessionOutcome.Nothing -> Unit
                is PossessionOutcome.Chance -> {
                    att.shots++
                    events += MatchEvent(minute, EventType.CHANCE, att.lineup.teamId, o.shooterId)
                    when {
                        o.scored -> { att.goals++; events += MatchEvent(minute, EventType.GOAL, att.lineup.teamId, o.shooterId, "${h.goals}-${a.goals}") }
                        o.saved -> events += MatchEvent(minute, EventType.SAVE, def.lineup.teamId, def.starters.firstOrNull { it.position == Position.GK }?.id)
                        else -> events += MatchEvent(minute, EventType.MISS, att.lineup.teamId, o.shooterId)
                    }
                }
            }
        }
        events += MatchEvent(sport.durationMinutes, EventType.FULLTIME, home.teamId, detail = "${h.goals}-${a.goals}")
        val total = (h.possessions + a.possessions).coerceAtLeast(1)
        return MatchResult(sport.id, seed, home.teamId, away.teamId, Score(h.goals, a.goals), events, h.possessions * 100 / total, h.shots, a.shots)
    }

    private fun fatigue(side: Side, amount: Double) {
        for (i in side.starters.indices) {
            val p = side.starters[i]
            side.starters[i] = p.copy(fatigue = (p.fatigue + amount).toInt().coerceIn(0, 100))
        }
    }

    private fun injuries(side: Side, minute: Int, rng: Rng, sport: SportProfile, events: MutableList<MatchEvent>) {
        if (rng.next() >= 0.0015) return
        val fit = side.starters.filter { !it.injured }
        if (fit.isEmpty()) return
        val victim = rng.pick(fit)
        val idx = side.starters.indexOf(victim)
        side.starters[idx] = victim.copy(injured = true)
        events += MatchEvent(minute, EventType.INJURY, side.lineup.teamId, victim.id)
        if (side.subs >= sport.maxSubs) return
        val sub = side.bench.firstOrNull { !it.injured && it.position == victim.position } ?: side.bench.firstOrNull { !it.injured } ?: return
        side.bench.remove(sub)
        side.starters[idx] = sub
        side.subs++
        events += MatchEvent(minute, EventType.SUBSTITUTION, side.lineup.teamId, sub.id, "for ${victim.id}")
    }
}
