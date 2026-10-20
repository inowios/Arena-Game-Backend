package com.arenacity.arena

/**
 * What chooses a side's turn: a bot, or a human (who builds the Turn itself and calls Engine.apply). A bot returns a
 * legal queue with every payment filled (Engine.autoPay). A port of the client's committed Ai.cs (the one-ply SmartAi);
 * the deeper two-ply bot of APP-AUDIT §2.7 slots in as another ArenaAi when it is ported.
 */
interface ArenaAi {
    val id: String
    /** The turn for [side] on the current state (the state is not modified). */
    fun choose(s: MatchState, side: Int): Turn
}

object AiFactory {
    /** "random" | "greedy" | "smart" (unknown → greedy). */
    fun create(id: String, seed: ULong): ArenaAi = when (id) {
        "random" -> RandomAi(seed)
        "smart" -> SmartAi(seed)
        else -> GreedyAi(seed)
    }
}

/**
 * The position value from [side]'s view: stamina, statuses (helpful ones from my side plus, the rival's on me minus),
 * energy — mine minus the rival's. Not a rule of the game, but both bots play by it.
 */
object Eval {
    fun score(s: MatchState, side: Int): Double = sideValue(s, side) - sideValue(s, 1 - side)

    fun sideValue(s: MatchState, side: Int): Double {
        val sd = s.sides[side]; var v = 0.0
        for (f in sd.fighters) {
            if (f == null) continue
            if (!f.alive) { v -= 50.0; continue }
            v += f.stamina
            if (f.passiveOn) v += 6.0
            for (st in f.statuses) {
                val t = if (st.permanent) 3 else minOf(st.turnsLeft, 3)
                val foreign = st.srcSide != side
                val filtered = (st.classes and SkillClass.TYPE_MASK) != 0 && (st.classes and SkillClass.TYPE_MASK) != SkillClass.TYPE_MASK
                when (st.kind) {
                    StatusKind.Reduce -> v += (if (st.percent) st.amount * 0.3 else st.amount * 0.6) * t
                    StatusKind.Defense -> v += minOf(60, st.amount) * 0.5
                    StatusKind.Invuln -> v += (if (filtered) 6 else 12) * t
                    StatusKind.Stun -> v -= (if (filtered) 12 else 22) * t
                    StatusKind.NoReduce, StatusKind.NoInvuln -> v -= 6 * t
                    StatusKind.Mark -> v += if (foreign) -(4 + st.amount * 0.8) * (if (st.permanent) 2 else t) else 2.0 * t
                    StatusKind.Weaken -> v -= st.amount * 0.8 * t
                    StatusKind.CostMod -> v -= 5 * t
                    StatusKind.Action -> v -= st.amount * st.turnsLeft * 0.9
                    StatusKind.Regen -> v += st.amount * 3
                    StatusKind.IgnoreHarm -> v += 8 * t
                    StatusKind.Reveal -> v += 2 * t
                    StatusKind.Counter, StatusKind.Reflect -> v += 12.0
                    StatusKind.Trap -> v -= 4.0
                    StatusKind.ExtraIncome -> v += 12.0
                    else -> {}
                }
            }
        }
        val e = sd.total; v += minOf(e, 9) * 4 + maxOf(0, e - 9)
        return v
    }
}

/** A random legal turn (the baseline): each living hero uses a random usable skill at a random target, or passes; the queue in a random order. */
class RandomAi(seed: ULong) : ArenaAi {
    private val rng = Rng(seed)
    override val id: String get() = "random"
    override fun choose(s: MatchState, side: Int): Turn {
        var t = Turn()
        if (s.finished || side != s.sideToMove) return t
        val order = mutableListOf(0, 1, 2)
        for (i in order.size - 1 downTo 1) { val j = rng.next(i + 1); val tmp = order[i]; order[i] = order[j]; order[j] = tmp }
        for (i in order) {
            if (rng.next(5) == 0) continue
            val opts = ArrayList<SkillUse>()
            for (k in 0 until 4) {
                if (Engine.unavailable(s, side, i, k) != null) continue
                val skill = s.sides[side].fighters[i]!!.current(k)!!
                val targets = Engine.targets(s, side, i, k)
                if (skill.target == TargetKind.ENEMY || skill.target == TargetKind.ALLY) for (tg in targets) opts.add(SkillUse(i, k, tg))
                else opts.add(SkillUse(i, k))
            }
            if (opts.isEmpty()) continue
            val trial = t.clone(); trial.queue.add(opts[rng.next(opts.size)])
            if (Engine.autoPay(s, side, trial) == null && Engine.checkTurn(s, side, trial) == null) t = trial
        }
        return t
    }
}

/**
 * Best immediate value: every (hero, skill, target) option is scored alone on a dry run, the combinations ranked by the
 * sum, the best [width] of them resolved for real (in slot order and, with two or more uses, in the reverse order too)
 * and the top one taken; one energy exchange is tried when the pool allows.
 */
class GreedyAi(seed: ULong) : ArenaAi {
    var width = 24; var exchanges = true
    private val rng = Rng(seed)
    var evals = 0L
    override val id: String get() = "greedy"

    class Cand(val turn: Turn, val score: Double, val after: MatchState)
    private class Opt(val use: SkillUse, val cost: CostVariant, val score: Double)
    private class Combo(val est: Double, val a: SkillUse?, val b: SkillUse?, val c: SkillUse?)

    override fun choose(s: MatchState, side: Int): Turn {
        val best = best(s, side, 1)
        return if (best.isNotEmpty()) best[0].turn else Turn()
    }

    /** The [n] best turns by immediate value, best first (each with its resolved state). */
    fun best(s: MatchState, side: Int, n: Int): MutableList<Cand> {
        val result = ArrayList<Cand>()
        if (s.finished || side != s.sideToMove) { result.add(Cand(Turn(), 0.0, s)); return result }
        search(s, side, null, n, result)
        if (exchanges && s.sides[side].total >= s.lib.rules.exchange + 1) {
            val pool = s.sides[side].pool; val need = BooleanArray(Colours.N)
            for (f in s.sides[side].fighters) if (f != null && f.alive) for (k in 0 until 4) { val sk = f.current(k); if (sk != null) for (c in 0 until Colours.N) if (sk.cost[c] > pool[c]) need[c] = true }
            for (c in 0 until Colours.N) {
                if (!need[c]) continue
                val x = Exchange(); x.get = c; var left = s.lib.rules.exchange
                while (left > 0) {
                    var from = -1; for (d in 0 until Colours.N) if (d != c && pool[d] - x.give[d] > 0 && (from < 0 || pool[d] - x.give[d] > pool[from] - x.give[from])) from = d
                    if (from < 0) break
                    x.give[from]++; left--
                }
                if (left > 0) continue
                search(s, side, x, n, result)
            }
        }
        result.sortWith(compareByDescending { it.score })
        while (result.size > n) result.removeAt(result.size - 1)
        return result
    }

    private fun search(s: MatchState, side: Int, x: Exchange?, n: Int, result: MutableList<Cand>) {
        val sd = s.sides[side]
        val pool = sd.pool.copyOf()
        if (x != null) { for (c in 0 until Colours.N) pool[c] -= x.give[c]; pool[x.get]++ }
        fun empty(): Turn { val t = Turn(); if (x != null) t.exchanges.add(x); return t }
        val (baseScore, st0) = score(s, side, empty())
        // single options, scored alone
        val opts = Array<MutableList<Opt>>(3) { ArrayList() }
        var anyOpt = false
        for (i in 0 until 3) {
            val f = sd.fighters[i]; if (f == null || !f.alive) continue
            for (k in 0 until 4) {
                if (Engine.blocked(s, side, i, k) != null) continue
                val skill = f.current(k)!!
                var cheapest: CostVariant? = null
                for (v in Engine.costVariants(s, f, skill)) if (v.affordable(pool) && (cheapest == null || v.total < cheapest.total)) cheapest = v
                if (cheapest == null) continue
                val targets = Engine.targets(s, side, i, k)
                val uses = ArrayList<SkillUse>()
                if (skill.target == TargetKind.ENEMY || skill.target == TargetKind.ALLY) for (tg in targets) uses.add(SkillUse(i, k, tg))
                else if (skill.target == TargetKind.ENEMIES && targets.isEmpty()) continue
                else uses.add(SkillUse(i, k))
                for (u in uses) {
                    val t = empty(); t.queue.add(u)
                    if (Engine.autoPay(s, side, t) != null) continue
                    val (sc, _) = score(s, side, t)
                    if (sc == Double.NEGATIVE_INFINITY) continue
                    opts[i].add(Opt(u, cheapest, sc - baseScore)); anyOpt = true
                }
            }
            opts[i].sortWith(compareByDescending { it.score })
        }
        if (!anyOpt) { result.add(Cand(empty(), baseScore, st0)); return }
        // combinations ranked by the additive estimate, the best width resolved for real
        val combos = ArrayList<Combo>()
        val o0 = opts[0]; val o1 = opts[1]; val o2 = opts[2]
        for (a in -1 until o0.size)
            for (b in -1 until o1.size)
                for (c in -1 until o2.size) {
                    var est = 0.0; val need = IntArray(Colours.N); var total = 0
                    if (a >= 0) { est += o0[a].score; total += add(need, o0[a].cost) }
                    if (b >= 0) { est += o1[b].score; total += add(need, o1[b].cost) }
                    if (c >= 0) { est += o2[c].score; total += add(need, o2[c].cost) }
                    var ok = true; var have = 0
                    for (col in 0 until Colours.N) { if (need[col] > pool[col]) { ok = false; break }; have += pool[col] }
                    if (!ok || total > have) continue
                    combos.add(Combo(est, if (a >= 0) o0[a].use else null, if (b >= 0) o1[b].use else null, if (c >= 0) o2[c].use else null))
                }
        combos.sortWith(compareByDescending { it.est })
        val w = minOf(width, combos.size)
        for (i in 0 until w) {
            val uses = ArrayList<SkillUse>(3)
            combos[i].a?.let { uses.add(it) }; combos[i].b?.let { uses.add(it) }; combos[i].c?.let { uses.add(it) }
            for (order in 0 until (if (uses.size >= 2) 2 else 1)) {
                val t = empty()
                if (order == 0) for (u in uses) t.queue.add(u.clone())
                else for (j in uses.size - 1 downTo 0) t.queue.add(uses[j].clone())
                if (Engine.autoPay(s, side, t) != null) continue
                val (sc, after) = score(s, side, t)
                if (sc == Double.NEGATIVE_INFINITY) continue
                result.add(Cand(t, sc, after))
            }
        }
        if (result.isEmpty()) result.add(Cand(empty(), baseScore, st0))
    }
    private fun add(need: IntArray, v: CostVariant): Int { for (c in 0 until Colours.N) need[c] += v.cost[c]; return v.total }

    /** The value of the position after [t] is resolved (dry run; the rival's income not yet drawn). */
    private fun score(s: MatchState, side: Int, t: Turn): Pair<Double, MatchState> {
        val after = s.clone(); evals++
        val err = Engine.resolve(after, t, null)
        if (err != null) return Double.NEGATIVE_INFINITY to after
        var v = Eval.score(after, side)
        if (after.finished) v += if (after.winner == side) 1000.0 else -1000.0
        return v to after
    }
}

/**
 * Greedy plus one ply of the rival: the [top] best immediate turns are each followed by the rival's likely reply (the
 * rival's energy drawn [samples] times, a lighter greedy answers each), and the turn that leaves the best average
 * position after that reply is played.
 */
class SmartAi(seed: ULong) : ArenaAi {
    var top = 4; var samples = 2; var immediateWeight = 0.1
    /** How often the lookahead overruled the greedy's first choice (diagnostics). */
    var decisions = 0; var overruled = 0
    private val mine = GreedyAi(seed xor 0x5EEDUL).also { it.width = 24 }
    private val reply = GreedyAi(seed xor 0xBEEFUL).also { it.width = 12; it.exchanges = false }
    private val rng = Rng(seed)
    val evals: Long get() = mine.evals + reply.evals
    override val id: String get() = "smart"

    override fun choose(s: MatchState, side: Int): Turn {
        if (s.finished || side != s.sideToMove) return Turn()
        val cands = mine.best(s, side, top)
        if (cands.size <= 1) return if (cands.size == 1) cands[0].turn else Turn()
        var best: Turn? = null; var bestV = Double.NEGATIVE_INFINITY; decisions++
        val draws = Array(samples) { rng.next() }
        for (c in cands) {
            var v = 0.0
            if (c.after.finished) v = c.score + (if (c.after.winner == side) 1000.0 else -1000.0)
            else
                for (i in 0 until samples) {
                    val st = c.after.clone()
                    st.rng = Rng(draws[i])   // a plausible income, not the real draw
                    Engine.advance(st, null)
                    if (st.finished) { v += (Eval.score(st, side) + (if (st.winner == side) 1000.0 else -1000.0)) / samples; continue }
                    val r = reply.best(st, 1 - side, 1)
                    val after = if (r.isNotEmpty()) r[0].after else st
                    v += (Eval.score(after, side) + (if (after.finished) (if (after.winner == side) 1000.0 else -1000.0) else 0.0)) / samples
                }
            v += c.score * immediateWeight   // a tie-break towards the stronger immediate position
            if (v > bestV) { bestV = v; best = c.turn }
        }
        if (best !== cands[0].turn) overruled++
        return best ?: Turn()
    }
}

/** Plays a whole match between two AIs. */
object Runner {
    fun play(s: MatchState, a: ArenaAi, b: ArenaAi, ev: MutableList<MatchEvent>? = null, maxTurns: Int = 200) {
        var guard = 0
        while (!s.finished && guard++ < maxTurns) {
            val ai = if (s.sideToMove == 0) a else b
            val t = ai.choose(s, s.sideToMove)
            val err = Engine.apply(s, t, ev)
            if (err != null) { ev?.add(MatchEvent("error", s.turn, s.sideToMove, text = ai.id + ": " + err + " (" + t + ")")); Engine.apply(s, Turn(), ev) }
        }
    }
}
