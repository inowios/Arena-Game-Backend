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
        /** In-match fatigue accrues in fractions per possession; whole points are applied to the starters. */
        var fatigueAcc = 0.0
        fun strength(sport: SportProfile) = Strength.of(lineup.copy(starters = starters), sport, isHome)
    }

    fun simulate(home: Lineup, away: Lineup, sport: SportProfile = FOOTBALL, seed: Long): MatchResult = when (sport.mode) {
        SimMode.TIMED -> timed(home, away, sport, seed)
        SimMode.SETS -> sets(home, away, sport, seed)
    }

    // ------------------------------------------------------------------ football / basketball

    private fun timed(home: Lineup, away: Lineup, sport: SportProfile, seed: Long): MatchResult {
        val rng = Rng(seed)
        val h = Side(home, true)
        val a = Side(away, false)
        var hs = h.strength(sport)
        var aws = a.strength(sport)
        val events = mutableListOf(MatchEvent(0, EventType.KICKOFF, home.teamId))
        val periods = mutableListOf<Score>()
        var periodStart = Score(0, 0)
        var nextPeriod = 1

        fun closePeriod(minute: Int) {
            periods += Score(h.goals - periodStart.home, a.goals - periodStart.away)
            periodStart = Score(h.goals, a.goals)
            if (sport.periods == 2 && nextPeriod == 1) events += MatchEvent(minute, EventType.HALFTIME, home.teamId, detail = "${h.goals}-${a.goals}")
            else if (nextPeriod < sport.periods) events += MatchEvent(minute, EventType.PERIOD, home.teamId, detail = "${h.goals}-${a.goals}", value = nextPeriod)
            nextPeriod++
        }

        fun possession(i: Int, minute: Int) {
            if (i > 0 && i % 20 == 0) { hs = h.strength(sport); aws = a.strength(sport) }
            val homeBall = if (sport.alternating) i % 2 == 0 else Possession.homeWins(hs, aws, rng)
            val att = if (homeBall) h else a
            val def = if (homeBall) a else h
            val attS = if (homeBall) hs else aws
            val defS = if (homeBall) aws else hs
            att.possessions++
            fatigue(att, attS.tempoCost * sport.fatigueRate)
            injuries(att, minute, rng, sport, events)
            when (val o = Possession.attack(attS, defS, att.starters, sport, rng)) {
                PossessionOutcome.Nothing -> Unit
                is PossessionOutcome.Chance -> {
                    att.shots++
                    events += MatchEvent(minute, EventType.CHANCE, att.lineup.teamId, o.shooterId, value = if (o.points != sport.scoreValue) o.points else null)
                    when {
                        o.scored -> { att.goals += o.points; events += MatchEvent(minute, EventType.GOAL, att.lineup.teamId, o.shooterId, "${h.goals}-${a.goals}", value = if (sport.scoreValue != 1 || o.points != 1) o.points else null) }
                        o.saved -> events += MatchEvent(minute, EventType.SAVE, def.lineup.teamId, keeperOf(def, sport)?.id)
                        else -> events += MatchEvent(minute, EventType.MISS, att.lineup.teamId, o.shooterId)
                    }
                }
            }
        }

        for (i in 0 until sport.possessions) {
            val minute = (i * sport.durationMinutes) / sport.possessions
            if (nextPeriod < sport.periods && i >= nextPeriod * sport.possessions / sport.periods) closePeriod(sport.durationMinutes * (nextPeriod) / sport.periods)
            possession(i, minute)
        }
        closePeriod(sport.durationMinutes)
        var overtime = false
        if (!sport.allowDraw) {
            var ot = 0
            val otMinutes = (sport.durationMinutes / sport.periods / 2).coerceAtLeast(1)
            while (h.goals == a.goals && ot < 3 && sport.overtimePossessions > 0) {
                overtime = true
                val base = sport.durationMinutes + ot * otMinutes
                events += MatchEvent(base, EventType.OVERTIME, home.teamId, detail = "${h.goals}-${a.goals}", value = ot + 1)
                for (j in 0 until sport.overtimePossessions) possession(sport.possessions + ot * sport.overtimePossessions + j, base + j * otMinutes / sport.overtimePossessions)
                periods += Score(h.goals - periodStart.home, a.goals - periodStart.away); periodStart = Score(h.goals, a.goals)
                ot++
            }
            var guard = 0
            while (h.goals == a.goals && guard++ < 200) possession(sport.possessions + 3 * sport.overtimePossessions + guard, sport.durationMinutes + 3 * otMinutes)
            if (h.goals == a.goals) { if (rng.next() < 0.5) h.goals++ else a.goals++ }   // unreachable in practice; keeps "no draws" absolute
        }
        val end = events.lastOrNull()?.minute ?: sport.durationMinutes
        events += MatchEvent(maxOf(end, sport.durationMinutes), EventType.FULLTIME, home.teamId, detail = "${h.goals}-${a.goals}")
        val total = (h.possessions + a.possessions).coerceAtLeast(1)
        return MatchResult(sport.id, seed, home.teamId, away.teamId, Score(h.goals, a.goals), events, h.possessions * 100 / total, h.shots, a.shots, periods, overtime)
    }

    private fun keeperOf(side: Side, sport: SportProfile): Athlete? {
        val keeperRole = sport.roles.maxByOrNull { it.value.keeper }?.key
        return side.starters.firstOrNull { it.position == keeperRole && !it.injured }
    }

    private fun fatigue(side: Side, amount: Double) {
        side.fatigueAcc += amount
        val whole = side.fatigueAcc.toInt()
        if (whole <= 0) return
        side.fatigueAcc -= whole
        for (i in side.starters.indices) {
            val p = side.starters[i]
            side.starters[i] = p.copy(fatigue = (p.fatigue + whole).coerceIn(0, 100))
        }
    }

    private fun injuries(side: Side, minute: Int, rng: Rng, sport: SportProfile, events: MutableList<MatchEvent>) {
        if (sport.injuryRate <= 0.0 || rng.next() >= sport.injuryRate) return
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

    // ------------------------------------------------------------------ tennis

    /**
     * Best-of-N sets. Each point is won by the server with p = serveWinBase + serveEdge × (s − r)/(s + r), where s and r are
     * the server's attack and the returner's defense (fatigue lowers both as the match goes on). 6-6 → tiebreak to 7 by 2.
     */
    private fun sets(home: Lineup, away: Lineup, sport: SportProfile, seed: Long): MatchResult {
        val rng = Rng(seed)
        val h = Side(home, true)
        val a = Side(away, false)
        val events = mutableListOf(MatchEvent(0, EventType.KICKOFF, home.teamId, h.starters.firstOrNull()?.id))
        val setScores = mutableListOf<Score>()
        var setsH = 0; var setsA = 0
        var homeServes = true
        var points = 0; var pointsH = 0; var pointsA = 0
        var minute = 0
        fun player(s: Side) = s.starters.firstOrNull()
        fun pointWonByServer(server: Side, returner: Side): Boolean {
            val ss = server.strength(sport); val rs = returner.strength(sport)
            val p = (sport.serveWinBase + sport.serveEdge * (ss.attack - rs.defense) / (ss.attack + rs.defense).coerceAtLeast(1.0)).coerceIn(0.30, 0.90)
            fatigue(server, ss.tempoCost * sport.fatigueRate); fatigue(returner, rs.tempoCost * sport.fatigueRate)
            points++; minute = points * 40 / 60
            return rng.next() < p
        }
        fun creditPoint(homeWon: Boolean) { if (homeWon) { pointsH++; h.shots++ } else { pointsA++; a.shots++ } }
        fun playGame(): Boolean {   // returns true when home wins the game
            val server = if (homeServes) h else a; val returner = if (homeServes) a else h
            var sp = 0; var rp = 0
            while (true) {
                val srv = pointWonByServer(server, returner)
                if (srv) sp++ else rp++
                creditPoint(srv == homeServes)
                if (sp >= 4 && sp - rp >= 2) return homeServes
                if (rp >= 4 && rp - sp >= 2) return !homeServes
            }
        }
        fun playTiebreak(): Boolean {
            var hp = 0; var ap = 0; var i = 0
            while (true) {
                val serverHome = if (i == 0) homeServes else ((i - 1) / 2) % 2 == 0 != homeServes
                val srv = pointWonByServer(if (serverHome) h else a, if (serverHome) a else h)
                val homeWon = srv == serverHome
                creditPoint(homeWon)
                if (homeWon) hp++ else ap++
                i++
                if (hp >= 7 && hp - ap >= 2) return true
                if (ap >= 7 && ap - hp >= 2) return false
            }
        }
        while (setsH < sport.setsToWin && setsA < sport.setsToWin) {
            var gh = 0; var ga = 0
            val setNo = setScores.size + 1
            while (true) {
                if (gh == sport.gamesPerSet && ga == sport.gamesPerSet) {
                    val homeWon = playTiebreak()
                    if (homeWon) gh++ else ga++
                    events += MatchEvent(minute, EventType.GAME, if (homeWon) home.teamId else away.teamId, player(if (homeWon) h else a)?.id, "$gh-$ga", setNo)
                    homeServes = !homeServes
                    break
                }
                val servingHome = homeServes
                val homeWon = playGame()
                if (homeWon) gh++ else ga++
                val broke = homeWon != servingHome
                events += MatchEvent(minute, if (broke) EventType.BREAK else EventType.GAME, if (homeWon) home.teamId else away.teamId, player(if (homeWon) h else a)?.id, "$gh-$ga", setNo)
                homeServes = !homeServes
                val lead = gh - ga
                if ((gh >= sport.gamesPerSet || ga >= sport.gamesPerSet) && kotlin.math.abs(lead) >= 2) break
                if (gh == sport.gamesPerSet + 1 || ga == sport.gamesPerSet + 1) break
            }
            setScores += Score(gh, ga)
            val homeSet = gh > ga
            if (homeSet) setsH++ else setsA++
            events += MatchEvent(minute, EventType.SET, if (homeSet) home.teamId else away.teamId, player(if (homeSet) h else a)?.id, "$gh-$ga", setNo)
        }
        h.goals = setsH; a.goals = setsA
        events += MatchEvent(minute, EventType.FULLTIME, home.teamId, detail = setScores.joinToString(" ") { "${it.home}-${it.away}" })
        val total = (pointsH + pointsA).coerceAtLeast(1)
        return MatchResult(sport.id, seed, home.teamId, away.teamId, Score(setsH, setsA), events, pointsH * 100 / total, pointsH, pointsA, setScores, false)
    }
}
