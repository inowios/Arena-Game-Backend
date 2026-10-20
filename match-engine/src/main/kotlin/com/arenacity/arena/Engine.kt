package com.arenacity.arena

/** A hero as a side picks it: the people.json id and the hero's level (kept for the result; v4 kits do not scale with it). */
class HeroPick(val id: String, val level: Int)

/** A cost a skill may be paid with now: colours + any (Чорна діра has several variants after Тиск маси / Орбіта). */
class CostVariant(val cost: IntArray, val any: Int) {
    val total: Int get() { var n = any; for (c in 0 until Colours.N) n += cost[c]; return n }
    fun affordable(pool: IntArray): Boolean { var total = 0; for (c in 0 until Colours.N) { if (pool[c] < cost[c]) return false; total += pool[c] }; return total >= this.total }
    /** Does a payment (energies per colour) pay exactly this cost: every colour covered, the rest as many as the «any». */
    fun paidBy(pay: IntArray?): Boolean { if (pay == null || pay.size != Colours.N) return false; var sum = 0; for (c in 0 until Colours.N) { if (pay[c] < 0 || pay[c] < cost[c]) return false; sum += pay[c] }; return sum == total }
    override fun toString(): String = SkillLibrary.costText(cost, any)
}

/**
 * The «Арена v4» engine — the Naruto-Arena rules (a port of the client's Engine.cs; docs/claude/ARENA-MATCH-V3.md §16):
 * alternating turns, one random energy per living hero at the start of the mover's turn (the first mover gets one on
 * turn 1), at most one skill per hero resolved in the queue's order, the damage pipeline of the original, statuses that
 * tick at the end of their owner's turn, 40 turns. The error strings are the client's Ukrainian ones: the cases check them.
 */
object Engine {
    // ---------- match ----------
    fun newMatch(lib: SkillLibrary, a: List<HeroPick>, b: List<HeroPick>, seed: ULong, ev: MutableList<MatchEvent>? = null, first: Int = -1): MatchState {
        val s = MatchState(Rng(seed), lib)
        s.sides[0] = newSide(lib, a, 0); s.sides[1] = newSide(lib, b, 1)
        s.first = if (first >= 0) first else s.rng.next(2)
        s.sideToMove = s.first; s.turn = 1
        beginTurn(s, ev)
        return s
    }
    private fun newSide(lib: SkillLibrary, picks: List<HeroPick>, side: Int): SideState {
        val sd = SideState()
        var i = 0
        while (i < 3 && i < picks.size) {
            val h = lib.hero(picks[i].id) ?: throw IllegalArgumentException("unknown hero ${picks[i].id}")
            val f = Fighter(h); f.level = picks[i].level.coerceIn(1, 10); f.slot = i; f.side = side; f.kit = h.kit(); f.stamina = lib.rules.stamina
            sd.fighters[i] = f
            i++
        }
        return sd
    }

    private fun log(ev: MutableList<MatchEvent>?, s: MatchState, kind: String, side: Int, slot: Int = -1, tside: Int = -1, tslot: Int = -1, value: Int = 0, text: String? = null) {
        ev?.add(MatchEvent(kind, s.turn, side, slot, tside, tslot, value, text))
    }
    fun poolText(pool: IntArray): String = "T${pool[0]} N${pool[1]} G${pool[2]} B${pool[3]}"

    // ---------- the start of a turn ----------
    /**
     * The mover's cooldowns tick; Control statuses whose caster lost contact end; the mover's ongoing (Action) skills hit
     * again (skipped while the caster is stunned for them or the target invulnerable); its Regen heals; then the energy
     * comes: one random per living hero (turn 1: rules.firstTurnIncome) plus ExtraIncome. Called by newMatch and advance.
     */
    private fun beginTurn(s: MatchState, ev: MutableList<MatchEvent>?) {
        val m = s.sideToMove; val side = s.sides[m]
        log(ev, s, "turn", m)
        for (f in side.fighters) if (f != null) for (k in 0 until 4) { if (f.cooldown[k] > 0) f.cooldown[k]--; if (f.altCooldown[k] > 0) f.altCooldown[k]-- }
        contact(s, ev)
        // the ongoing skills of the mover: every Action status it sourced
        for (sd in 0 until 2)
            fighters@ for (f in s.sides[sd].fighters) {
                if (f == null || !f.alive) continue
                var i = 0
                while (i < f.statuses.size) {
                    val st = f.statuses[i]
                    if (st.kind != StatusKind.Action || st.srcSide != m) { i++; continue }
                    val caster = s.sides[st.srcSide].fighters[st.srcSlot]; val skill = s.lib.skill(st.skill)
                    val e = if (skill != null && st.effectIndex >= 0 && st.effectIndex < skill.effects.size) skill.effects[st.effectIndex] else null
                    if (caster == null || !caster.alive || skill == null || e == null) { f.statuses.removeAt(i); log(ev, s, "contact", st.srcSide, st.srcSlot, sd, f.slot, 0, st.skill + "|caster out"); continue }
                    if (stunnedFor(caster, skill)) log(ev, s, "skip", m, caster.slot, sd, f.slot, st.amount, st.skill + "|caster stunned")
                    else if (sd != m && !e.ignoreInvuln && invulnerable(s, caster, skill, f)) log(ev, s, "skip", m, caster.slot, sd, f.slot, st.amount, st.skill + "|target invulnerable")
                    else {
                        val amount = amount(s, caster, skill, e, f); st.amount = amount
                        log(ev, s, "tick", m, caster.slot, sd, f.slot, amount, st.skill)
                        dealDamage(s, caster, skill, e, f, amount, ev, st.skill + "|tick")
                        if (!f.alive) continue@fighters
                        i = f.statuses.indexOf(st); if (i < 0) continue@fighters
                    }
                    st.turnsLeft--
                    if (st.turnsLeft <= 0) { f.statuses.removeAt(i); log(ev, s, "expire", sd, f.slot, text = "Action|" + st.skill) } else i++
                }
            }
        for (f in side.fighters) {
            if (f == null || !f.alive) continue
            for (st in ArrayList(f.statuses))
                if (st.kind == StatusKind.Regen) { val before = f.stamina; f.stamina = minOf(s.lib.rules.stamina, f.stamina + st.amount); log(ev, s, "tick", m, f.slot, m, f.slot, f.stamina - before, st.skill + "|regen") }
        }
        if (checkEnd(s, ev)) return
        var n = side.aliveCount
        if (s.turn == 1) n = minOf(n, s.lib.rules.firstTurnIncome)
        for (f in side.fighters) if (f != null && f.alive) for (st in f.statuses) if (st.kind == StatusKind.ExtraIncome) n += st.amount
        val got = IntArray(Colours.N)
        for (i in 0 until n) { val colour = s.rng.next(Colours.N); side.pool[colour]++; got[colour]++ }
        log(ev, s, "income", m, value = n, text = poolText(got))
    }
    /** Control statuses need the caster's contact: the caster alive, not stunned for the skill, the target not invulnerable to it. */
    private fun contact(s: MatchState, ev: MutableList<MatchEvent>?) {
        for (sd in 0 until 2)
            for (f in s.sides[sd].fighters) {
                if (f == null) continue
                var i = f.statuses.size - 1
                while (i >= 0) {
                    val st = f.statuses[i]
                    if (!st.control) { i--; continue }
                    val caster = s.sides[st.srcSide].fighters[st.srcSlot]; val skill = s.lib.skill(st.skill)
                    val why = if (caster == null || !caster.alive) "caster out" else if (skill == null) "no skill" else if (stunnedFor(caster, skill)) "caster stunned" else if (sd != st.srcSide && invulnerable(s, caster, skill, f)) "target invulnerable" else null
                    if (why == null) { i--; continue }
                    f.statuses.removeAt(i); log(ev, s, "contact", st.srcSide, st.srcSlot, sd, f.slot, 0, "$st|${st.skill}|$why")
                    i--
                }
            }
    }

    // ---------- legality ----------
    /** Is the fighter stunned for the skill (a Stun whose type filter covers the skill's type). */
    fun stunnedFor(f: Fighter, skill: SkillDef): Boolean {
        for (st in f.statuses) if (st.kind == StatusKind.Stun && SkillClasses.covers(st.classes, skill.type)) return true
        return false
    }
    /** Does the skill ignore invulnerability now (its condition holds for the caster). */
    fun ignoresInvuln(s: MatchState, caster: Fighter, skill: SkillDef): Boolean = skill.ignoresInvuln.kind != CondKind.None && condHolds(s, caster, skill.ignoresInvuln, null)
    /** Is [t] invulnerable to [skill] of [caster]: an Invuln covering the skill's type, unless t cannot become invulnerable or the skill ignores it. */
    fun invulnerable(s: MatchState, caster: Fighter, skill: SkillDef, t: Fighter): Boolean {
        if (ignoresInvuln(s, caster, skill) || t.has(StatusKind.NoInvuln)) return false
        for (st in t.statuses) if (st.kind == StatusKind.Invuln && SkillClasses.covers(st.classes, skill.type)) return true
        return false
    }
    /** Can [caster] aim the harmful [skill] at [t]: alive, not invulnerable to it, not already affected when the skill forbids that. */
    fun targetable(s: MatchState, caster: Fighter, skill: SkillDef, t: Fighter?): Boolean {
        if (t == null || !t.alive) return false
        if (skill.notOnAffected && t.hasFrom(skill.id)) return false
        return !invulnerable(s, caster, skill, t)
    }
    /** The fighter indices the skill may aim at (Enemy / Enemies: on the rival side; Ally: on the own side, the caster included); empty for Self / Allies / None. */
    fun targets(s: MatchState, side: Int, slot: Int, k: Int): MutableList<Int> {
        val r = ArrayList<Int>()
        val f = s.sides[side].fighters[slot]; val skill = f?.current(k) ?: return r
        if (skill.target == TargetKind.ENEMY || skill.target == TargetKind.ENEMIES) { val o = s.sides[1 - side]; for (i in 0 until 3) if (targetable(s, f, skill, o.fighters[i])) r.add(i) }
        else if (skill.target == TargetKind.ALLY) { for (i in 0 until 3) { val a = s.sides[side].fighters[i]; if (a != null && a.alive) r.add(i) } }
        return r
    }

    /**
     * What the skill costs now: the base cost changed by its own conditions (Розряд cheaper during Гроза, Шахтарський дух +2
     * any per use, Чорна діра's variants after Тиск маси / Орбіта) and by the CostMod statuses on the fighter.
     */
    fun costVariants(s: MatchState, f: Fighter, skill: SkillDef): MutableList<CostVariant> {
        var extra = 0; val variants = ArrayList<CostVariant>(); variants.add(CostVariant(skill.cost, skill.any))
        for (m in skill.costMods) {
            if (!condHolds(s, f, m.cond, null)) continue
            if (m.replace) { variants.add(CostVariant(m.cost!!, m.any)); continue }
            val k = if (m.per == PerKind.Uses) f.uses(m.perSkill!!) else 1
            extra += m.any * k
        }
        for (st in f.statuses)
            if (st.kind == StatusKind.CostMod && SkillClasses.covers(st.classes, skill.type) && (st.param != 1 || !skill.harmful)) extra += st.amount
        if (extra != 0) for (i in variants.indices) variants[i] = CostVariant(variants[i].cost, maxOf(0, variants[i].any + extra))
        return variants
    }
    fun costOf(s: MatchState, side: Int, slot: Int, k: Int): List<CostVariant> {
        val f = s.sides[side].fighters[slot]; val skill = f?.current(k)
        return if (skill == null) ArrayList() else costVariants(s, f, skill)
    }
    private fun affordable(s: MatchState, f: Fighter, skill: SkillDef, pool: IntArray): Boolean { for (v in costVariants(s, f, skill)) if (v.affordable(pool)) return true; return false }

    /** Why fighter [slot] of [side] cannot use kit slot [k] regardless of energy and targets (null = it can). */
    fun blocked(s: MatchState, side: Int, slot: Int, k: Int): String? {
        if (s.finished) return "матч завершено"
        if (side != s.sideToMove) return "не ваш хід"
        val f = s.sides[side].fighters[slot]
        if (f == null || !f.alive) return "видихся"
        val skill = if (k in 0..3) f.current(k) else null
        if (skill == null) return "немає здібності"
        val cd = f.cooldownOf(k); if (cd > 0) return "перезарядка $cd"
        if (stunnedFor(f, skill)) return "збитий з ритму"
        if (skill.requires != null && !f.hasFrom(skill.requires)) return "потребує " + (s.lib.skill(skill.requires)?.name ?: skill.requires)
        if (skill.maxUses > 0 && f.uses(skill.id) >= skill.maxUses) return "вичерпано"
        return null
    }
    /** Null when fighter [slot] of [side] may use kit slot [k] now, else the reason (for a greyed icon). */
    fun unavailable(s: MatchState, side: Int, slot: Int, k: Int): String? {
        val why = blocked(s, side, slot, k); if (why != null) return why
        val f = s.sides[side].fighters[slot]!!; val skill = f.current(k)!!
        if (!affordable(s, f, skill, s.sides[side].pool)) return "не вистачає енергії"
        if ((skill.target == TargetKind.ENEMY || skill.target == TargetKind.ENEMIES || skill.target == TargetKind.ALLY) && targets(s, side, slot, k).isEmpty()) return "немає цілі"
        return null
    }

    /**
     * Fills the payment of every use of [t] in the queue's order: the cheapest affordable cost variant, its colours, then
     * each «any» from the most plentiful colour left (ties: the colour the side's kits need least). Null, or the error
     * when a use cannot be paid (the payments already filled stay).
     */
    fun autoPay(s: MatchState, side: Int, t: Turn): String? {
        val sd = s.sides[side]; val pool = sd.pool.copyOf()
        for (x in t.exchanges) { for (c in 0 until Colours.N) pool[c] -= x.give[c]; pool[x.get]++ }
        val demand = IntArray(Colours.N)
        for (f in sd.fighters) if (f != null && f.alive) for (sk in f.kit) if (sk != null) for (c in 0 until Colours.N) demand[c] += sk.cost[c]
        for (u in t.queue) {
            val f = if (u.slot in 0..2) sd.fighters[u.slot] else null; val skill = f?.current(u.skill)
            if (f == null || skill == null) return "герой ${u.slot}: немає здібності ${u.skill}"
            var best: CostVariant? = null
            for (v in costVariants(s, f, skill)) if (v.affordable(pool) && (best == null || v.total < best.total)) best = v
            if (best == null) return "герой ${u.slot}: ${skill.name} — не вистачає енергії"
            val pay = best.cost.copyOf()
            for (c in 0 until Colours.N) pool[c] -= pay[c]
            for (a in 0 until best.any) {
                var bestC = -1
                for (c in 0 until Colours.N) if (pool[c] > 0 && (bestC < 0 || pool[c] > pool[bestC] || (pool[c] == pool[bestC] && demand[c] < demand[bestC]))) bestC = c
                if (bestC < 0) return "герой ${u.slot}: ${skill.name} — не вистачає енергії"
                pool[bestC]--; pay[bestC]++
            }
            u.pay = pay
        }
        return null
    }

    /**
     * Null when the turn is legal for [side], else the first error: the exchanges, then every use of the queue (one per
     * fighter; the skill usable; the target legal on the state before the turn; the payment matching a cost variant) and
     * the energy running through the queue.
     */
    fun checkTurn(s: MatchState, side: Int, t: Turn): String? {
        if (s.finished) return "матч завершено"
        if (side != s.sideToMove) return "не ваш хід"
        val pool = s.sides[side].pool.copyOf()
        for (x in t.exchanges) {
            if (x.give.size != Colours.N || x.get < 0 || x.get >= Colours.N) return "обмін: неправильні дані"
            var sum = 0; for (c in 0 until Colours.N) { if (x.give[c] < 0 || x.give[c] > pool[c]) return "обмін: немає стільки енергії"; sum += x.give[c] }
            if (sum != s.lib.rules.exchange) return "обмін: потрібно ${s.lib.rules.exchange} енергій"
            for (c in 0 until Colours.N) pool[c] -= x.give[c]; pool[x.get]++
        }
        val seen = BooleanArray(3)
        for (u in t.queue) {
            val i = u.slot
            if (i < 0 || i > 2) return "черга: герой $i"
            if (seen[i]) return "герой $i: двічі в черзі"
            seen[i] = true
            val f = s.sides[side].fighters[i]
            val why = blocked(s, side, i, u.skill)
            if (why != null) return "герой $i: ${f?.current(u.skill)?.name ?: "?"} — $why"
            val skill = f!!.current(u.skill)!!
            if (skill.target == TargetKind.ENEMY || skill.target == TargetKind.ALLY) {
                if (u.target < 0 || u.target > 2) return "герой $i: ${skill.name} — потрібна ціль"
                if (!targets(s, side, i, u.skill).contains(u.target)) return "герой $i: ${skill.name} — ціль ${u.target} недоступна"
            } else if (skill.target == TargetKind.ENEMIES && targets(s, side, i, u.skill).isEmpty()) return "герой $i: ${skill.name} — немає цілі"
            val pay = u.pay ?: return "герой $i: ${skill.name} — не вказано оплату"
            var paid = false; for (v in costVariants(s, f, skill)) if (v.paidBy(pay)) { paid = true; break }
            if (!paid) return "герой $i: ${skill.name} — оплата ${poolText(pay)} не відповідає ціні"
            for (c in 0 until Colours.N) { pool[c] -= pay[c]; if (pool[c] < 0) return "не вистачає енергії (${Colours.NAMES_UK[c]})" }
        }
        return null
    }

    // ---------- a turn ----------
    /** Resolves [t] for the side to move and begins the rival's turn. Returns null, or the error of checkTurn (the state untouched). */
    fun apply(s: MatchState, t: Turn, ev: MutableList<MatchEvent>? = null): String? {
        val err = resolve(s, t, ev); if (err != null) return err
        advance(s, ev)
        return null
    }
    /**
     * The first half of apply: the exchanges, the queue in order (each use paid as it resolves), the end-of-turn tick of
     * the mover's statuses, the end check. The turn is not advanced (an AI evaluates the position here).
     */
    fun resolve(s: MatchState, t: Turn, ev: MutableList<MatchEvent>? = null): String? {
        val m = s.sideToMove
        val err = checkTurn(s, m, t); if (err != null) return err
        val side = s.sides[m]
        for (x in t.exchanges) {
            for (c in 0 until Colours.N) side.pool[c] -= x.give[c]
            side.pool[x.get]++; side.exchanges++
            log(ev, s, "exchange", m, value = x.get, text = "${poolText(x.give)} → ${Colours.NAMES[x.get]}")
        }
        if (t.queue.size > 0) log(ev, s, "queue", m, value = t.queue.size, text = t.toString())
        for (u in t.queue) {
            val pay = u.pay!!
            for (c in 0 until Colours.N) side.pool[c] -= pay[c]
            resolveUse(s, m, u, ev)
            if (s.finished) break
        }
        // the end of the mover's turn: the statuses on its fighters tick (those applied this turn wait; Action keeps its own clock; permanent ones stay)
        for (f in side.fighters) {
            if (f == null) continue
            var i = f.statuses.size - 1
            while (i >= 0) {
                val st = f.statuses[i]
                if (!(st.kind == StatusKind.Action || st.permanent || st.appliedTurn == s.turn)) {
                    st.turnsLeft--; if (st.turnsLeft <= 0) { f.statuses.removeAt(i); log(ev, s, "expire", m, f.slot, text = "${st.kind}|${st.skill}") }
                }
                i--
            }
        }
        if (checkEnd(s, ev)) return null
        if (s.turn >= s.lib.rules.maxTurns) {
            val a = s.sides[0].staminaTotal; val b = s.sides[1].staminaTotal
            s.finished = true; s.winner = if (a > b) 0 else if (b > a) 1 else 1 - s.first
            log(ev, s, "end", s.winner, value = a - b, text = "${s.lib.rules.maxTurns} turns: stamina $a v $b" + (if (a == b) " (tie → the second mover)" else ""))
        }
        return null
    }
    /** The second half of apply: the next turn begins for the rival (its cooldowns, ongoing skills, energy). */
    fun advance(s: MatchState, ev: MutableList<MatchEvent>? = null) {
        if (s.finished) return
        s.turn++; s.sideToMove = 1 - s.sideToMove
        beginTurn(s, ev)
    }

    private fun checkEnd(s: MatchState, ev: MutableList<MatchEvent>?): Boolean {
        if (s.finished) return true
        val d0 = s.sides[0].aliveCount == 0; val d1 = s.sides[1].aliveCount == 0
        if (!d0 && !d1) return false
        s.finished = true; s.winner = if (d0 && d1) 1 - s.sideToMove else if (d0) 1 else 0
        log(ev, s, "end", s.winner, text = "all three rivals are out")
        return true
    }

    // ---------- a skill ----------
    private fun resolveUse(s: MatchState, m: Int, u: SkillUse, ev: MutableList<MatchEvent>?) {
        val side = s.sides[m]; val other = s.sides[1 - m]
        val caster = side.fighters[u.slot]!!; val skill = caster.current(u.skill)!!
        if (!caster.alive) { log(ev, s, "fizzle", m, u.slot, text = skill.id + "|caster out"); return }
        if (caster.isAlt(u.skill)) caster.altCooldown[u.skill] = skill.cooldown + 1 else caster.cooldown[u.skill] = skill.cooldown + 1
        side.skillsUsed++; if (skill.isInvulnSlot) side.invulnUsed++
        log(ev, s, "cast", m, u.slot, if (skill.harmful) 1 - m else if (skill.target == TargetKind.NONE) -1 else m, u.target, skill.totalCost, skill.id + "|" + skill.name)
        // Пастка springs on a harmful skill: the trap's mark on the caster for 2 turns
        if (skill.harmful) {
            var i = caster.statuses.size - 1
            while (i >= 0) {
                val tr = caster.statuses[i]
                if (tr.kind == StatusKind.Trap) {
                    caster.statuses.removeAt(i)
                    val mark = Status(); mark.kind = StatusKind.Mark; mark.turnsLeft = 2; mark.appliedTurn = s.turn; mark.srcSide = tr.srcSide; mark.srcSlot = tr.srcSlot; mark.skill = tr.skill; mark.effectIndex = -2
                    caster.statuses.add(mark)
                    log(ev, s, "trap", tr.srcSide, tr.srcSlot, m, caster.slot, 0, "$mark|${tr.skill}|${skill.id}")
                }
                i--
            }
        }
        // targets
        val targets = ArrayList<Fighter>(3)
        when (skill.target) {
            TargetKind.ENEMY -> { val t = if (u.target in 0..2) other.fighters[u.target] else null; if (t != null && targetable(s, caster, skill, t)) targets.add(t) }
            TargetKind.ALLY -> { val t = if (u.target in 0..2) side.fighters[u.target] else null; if (t != null && t.alive) targets.add(t) }
            TargetKind.SELF -> targets.add(caster)
            TargetKind.ENEMIES -> for (t in other.fighters) if (targetable(s, caster, skill, t)) targets.add(t!!)
            TargetKind.ALLIES -> for (t in side.fighters) if (t != null && t.alive) targets.add(t)
            TargetKind.NONE -> {}
        }
        if (targets.isEmpty() && skill.target != TargetKind.NONE) { log(ev, s, "fizzle", m, u.slot, text = skill.id + "|no target"); used(s, caster, skill, ev); return }
        if (skill.harmful) {
            if (!skill.uncounterable)
                for (t in targets) {
                    var ct: Status? = null; for (st in t.statuses) if (st.kind == StatusKind.Counter && SkillClasses.covers(st.classes, skill.type)) { ct = st; break }
                    if (ct == null) continue
                    t.statuses.remove(ct)
                    log(ev, s, "counter", t.side, t.slot, m, u.slot, 0, skill.id + "|" + ct.skill)
                    used(s, caster, skill, ev)
                    return
                }
            if (!skill.unreflectable)
                for (i in targets.indices) {
                    var rf: Status? = null; for (st in targets[i].statuses) if (st.kind == StatusKind.Reflect && SkillClasses.covers(st.classes, skill.type)) { rf = st; break }
                    if (rf == null) continue
                    targets[i].statuses.remove(rf)
                    log(ev, s, "reflect", targets[i].side, targets[i].slot, m, u.slot, 0, skill.id + "|" + rf.skill)
                    targets[i] = caster
                }
        }
        var i = 0
        while (i < skill.effects.size && !s.finished) { applyEffect(s, caster, skill, skill.effects[i], i, targets, ev); i++ }
        if (skill.alt != null && caster.alive) {
            val st = addStatus(s, caster, caster, skill, StatusKind.Alt, 0, skill.altTurns, -1, ev, { x -> x.param = u.skill; x.altSkill = skill.alt }, false)
            log(ev, s, "alt", m, caster.slot, m, caster.slot, u.skill, skill.alt + "|" + skill.id + "×" + st.turnsLeft)
        }
        used(s, caster, skill, ev)
    }
    /** The use is counted after the skill resolves (Шахтарський дух's «each use» reads the earlier ones); a passive on uses may fire. */
    private fun used(s: MatchState, caster: Fighter, skill: SkillDef, ev: MutableList<MatchEvent>?) {
        caster.uses[skill.id] = caster.uses(skill.id) + 1
        if (caster.alive) checkPassive(s, caster, ev)
    }

    private fun applyEffect(s: MatchState, caster: Fighter, skill: SkillDef, e: Effect, idx: Int, targets: List<Fighter>, ev: MutableList<MatchEvent>?) {
        val m = caster.side; val side = s.sides[m]; val other = s.sides[1 - m]
        if (e.cond.kind != CondKind.None && e.cond.kind != CondKind.TargetHas && !condHolds(s, caster, e.cond, null)) return
        val rec: MutableList<Fighter>
        when (e.to) {
            EffectTo.Self -> { rec = ArrayList(1); rec.add(caster) }
            EffectTo.Allies -> { rec = ArrayList(3); for (f in side.fighters) if (f != null && f.alive) rec.add(f) }
            EffectTo.Enemies -> { rec = ArrayList(3); for (f in other.fighters) if (f != null && f.alive && (e.ignoreInvuln || !invulnerable(s, caster, skill, f))) rec.add(f) }
            EffectTo.OtherEnemies, EffectTo.RandomOtherEnemy -> {
                rec = ArrayList(3); val primary = if (targets.isNotEmpty()) targets[0] else null
                for (f in other.fighters) if (f != null && f.alive && f !== primary && (e.ignoreInvuln || !invulnerable(s, caster, skill, f))) rec.add(f)
                if (e.to == EffectTo.RandomOtherEnemy && rec.size > 1) { val one = rec[s.rng.next(rec.size)]; rec.clear(); rec.add(one) }
            }
            else -> rec = ArrayList(targets)
        }
        for (t in rec) {
            if (!t.alive) continue
            if (e.cond.kind == CondKind.TargetHas && !condHolds(s, caster, e.cond, t)) continue
            val enemy = t.side != caster.side
            if (enemy && e.kind != EffectKind.Damage && e.kind != EffectKind.Heal && !e.unignorable && t.has(StatusKind.IgnoreHarm)) { log(ev, s, "ignored", t.side, t.slot, m, caster.slot, 0, "${e.kind}|${skill.id}"); continue }
            val amount = amount(s, caster, skill, e, t)
            val turns = duration(s, caster, e, t)
            when (e.kind) {
                EffectKind.Damage -> {
                    dealDamage(s, caster, skill, e, t, amount, ev, skill.id)
                    if (e.action && turns > 1 && t.alive) addStatus(s, t, caster, skill, StatusKind.Action, amount, turns - 1, idx, ev, { x -> x.control = e.control })
                }
                EffectKind.Heal -> { val before = t.stamina; t.stamina = minOf(s.lib.rules.stamina, t.stamina + amount); log(ev, s, "heal", m, caster.slot, t.side, t.slot, t.stamina - before, skill.id) }
                EffectKind.Reduce -> addStatus(s, t, caster, skill, StatusKind.Reduce, amount, turns, idx, ev, { x -> x.unpierceable = e.unpierceable; x.percent = e.percent; x.permanent = e.permanent; x.control = e.control })
                EffectKind.Defense -> addStatus(s, t, caster, skill, StatusKind.Defense, amount, turns, idx, ev, { x -> x.permanent = e.permanent || turns <= 0 })
                EffectKind.Invuln -> addStatus(s, t, caster, skill, StatusKind.Invuln, 0, turns, idx, ev, { x -> x.classes = e.classes; x.control = e.control })
                EffectKind.Stun -> addStatus(s, t, caster, skill, StatusKind.Stun, 0, turns, idx, ev, { x -> x.classes = e.classes; x.control = e.control })
                EffectKind.NoReduce -> addStatus(s, t, caster, skill, StatusKind.NoReduce, 0, turns, idx, ev, { x -> x.control = e.control })
                EffectKind.NoInvuln -> addStatus(s, t, caster, skill, StatusKind.NoInvuln, 0, turns, idx, ev, { x -> x.control = e.control })
                EffectKind.Mark -> addStatus(s, t, caster, skill, StatusKind.Mark, amount, turns, idx, ev, { x -> x.permanent = e.permanent; x.stacks = e.stacks; x.control = e.control })
                EffectKind.Weaken -> addStatus(s, t, caster, skill, StatusKind.Weaken, amount, turns, idx, ev, { x -> x.classes = e.classes })
                EffectKind.CostMod -> addStatus(s, t, caster, skill, StatusKind.CostMod, amount, turns, idx, ev, { x -> x.classes = e.classes; x.param = e.param; x.unignorable = e.unignorable; x.control = e.control })
                EffectKind.Regen -> addStatus(s, t, caster, skill, StatusKind.Regen, amount, turns, idx, ev, { x -> x.permanent = e.permanent || turns <= 0 })
                EffectKind.IgnoreHarm -> addStatus(s, t, caster, skill, StatusKind.IgnoreHarm, 0, turns, idx, ev, null)
                EffectKind.Reveal -> addStatus(s, t, caster, skill, StatusKind.Reveal, 0, turns, idx, ev, null)
                EffectKind.Counter -> addStatus(s, t, caster, skill, StatusKind.Counter, 0, turns, idx, ev, { x -> x.classes = e.classes })
                EffectKind.Reflect -> addStatus(s, t, caster, skill, StatusKind.Reflect, 0, turns, idx, ev, { x -> x.classes = e.classes })
                EffectKind.Trap -> addStatus(s, t, caster, skill, StatusKind.Trap, 0, turns, idx, ev, { x -> x.invisible = true })
                EffectKind.Used -> addStatus(s, t, caster, skill, StatusKind.Used, 0, turns, idx, ev, null)
                EffectKind.ExtraIncome -> addStatus(s, t, caster, skill, StatusKind.ExtraIncome, amount, turns, idx, ev, { x -> x.permanent = e.permanent || turns <= 0 })
                EffectKind.RemoveEnergy -> {
                    val from = s.sides[t.side]; val mask = e.classes and 15
                    for (i in 0 until amount) {
                        val c = randomEnergy(s, from, mask)
                        if (c < 0) { log(ev, s, "energy", m, caster.slot, t.side, -1, 0, "remove nothing"); break }
                        from.pool[c]--; log(ev, s, "energy", m, caster.slot, t.side, -1, -1, "remove " + Colours.NAMES[c])
                    }
                }
            }
            if (s.finished) return
        }
    }

    /** Does a condition hold for the caster (and the recipient [t] when it is about the target). */
    fun condHolds(s: MatchState, caster: Fighter, c: Cond, t: Fighter?): Boolean = when (c.kind) {
        CondKind.None, CondKind.Always -> true
        CondKind.Passive -> caster.passiveOn
        CondKind.During -> caster.hasFrom(c.skill)
        CondKind.After -> { var r = false; for (st in caster.statuses) if (st.kind == StatusKind.Used && st.skill == c.skill) { r = true; break }; r }
        CondKind.TargetHas -> t != null && t.hasFrom(c.skill)
        CondKind.Uses -> caster.uses(c.skill!!) >= c.n
    }
    /** The effect's amount for recipient [t]: the base plus every bonus whose condition holds (scaled by uses / stacks). */
    fun amount(s: MatchState, caster: Fighter, skill: SkillDef, e: Effect, t: Fighter?): Int {
        var amount = e.amount
        for (b in e.bonuses) {
            if (!condHolds(s, caster, b.cond, t)) continue
            val k = if (b.per == PerKind.Uses) caster.uses(b.perSkill!!) else if (b.per == PerKind.Stacks) (t?.stacks(b.perSkill) ?: 0) else 1
            amount += b.amount * k
        }
        return amount
    }
    private fun duration(s: MatchState, caster: Fighter, e: Effect, t: Fighter): Int {
        var turns = e.turns
        for (x in e.extends) if (condHolds(s, caster, x.cond, t)) turns = if (x.replace) x.turns else turns + x.turns
        return turns
    }
    /** A random energy of the side among the colours of [mask] (0 = any); −1 when there is none. */
    private fun randomEnergy(s: MatchState, side: SideState, mask: Int): Int {
        var total = 0; for (c in 0 until Colours.N) if (mask == 0 || (mask and (1 shl c)) != 0) total += side.pool[c]
        if (total <= 0) return -1
        var pick = s.rng.next(total)
        for (c in 0 until Colours.N) { if (mask != 0 && (mask and (1 shl c)) == 0) continue; if (pick < side.pool[c]) return c; pick -= side.pool[c] }
        return -1
    }
    /**
     * Adds a status; the same effect of the same skill of the same caster refreshes instead of stacking (unless the effect
     * stacks); statuses of different skills or effects stack. Logged as «status», or «invisible» when the rival must not see it.
     */
    private fun addStatus(s: MatchState, t: Fighter, caster: Fighter, skill: SkillDef, kind: StatusKind, amount: Int, turns: Int, effectIndex: Int, ev: MutableList<MatchEvent>?, set: ((Status) -> Unit)?, log: Boolean = true): Status {
        val st = Status(); st.kind = kind; st.amount = amount; st.turnsLeft = maxOf(1, turns); st.appliedTurn = s.turn; st.srcSide = caster.side; st.srcSlot = caster.slot; st.skill = skill.id; st.effectIndex = effectIndex
        set?.invoke(st)
        if (skill.effects.size > effectIndex && effectIndex >= 0 && skill.effects[effectIndex].invisible) st.invisible = true
        var old: Status? = null
        if (!st.stacks) for (x in t.statuses) if (x.kind == kind && x.skill == skill.id && x.srcSide == caster.side && x.srcSlot == caster.slot && x.effectIndex == effectIndex) { old = x; break }
        if (old != null) { val i = t.statuses.indexOf(old); if (kind == StatusKind.Defense) st.amount = maxOf(old.amount, st.amount); t.statuses[i] = st }
        else t.statuses.add(st)
        if (log) log(ev, s, if (st.invisible) "invisible" else "status", caster.side, caster.slot, t.side, t.slot, amount, "$st|${skill.id}")
        return st
    }

    /**
     * Damage to [t] from [src] with [skill]'s effect [e] (null = plain damage): the source's Weaken cuts it (not affliction),
     * the target's damage reduction cuts it (piercing: only the unpierceable; affliction: none; none while it cannot reduce
     * damage) — the percent reductions first, then the points — then its destructible defense absorbs (not affliction), the
     * rest leaves the stamina; a fighter at 0 is out and loses its statuses, its ongoing and Control skills end; a passive at 50 may fire.
     */
    fun dealDamage(s: MatchState, src: Fighter?, skill: SkillDef?, e: Effect?, t: Fighter, amount: Int, ev: MutableList<MatchEvent>?, tag: String) {
        val affliction = e != null && e.affliction; val piercing = e != null && e.piercing
        var raw = amount
        if (src != null && skill != null && !affliction) for (w in src.statuses) if (w.kind == StatusKind.Weaken && SkillClasses.covers(w.classes, skill.type)) raw -= w.amount
        raw = maxOf(0, raw)
        var dmg = raw; var reduced = 0; var absorbed = 0
        if (!affliction) {
            if (!t.has(StatusKind.NoReduce)) {
                var pct = 0; var flat = 0
                for (r in t.statuses) { if (r.kind != StatusKind.Reduce || (piercing && !r.unpierceable)) continue; if (r.percent) pct += r.amount else flat += r.amount }
                var after = dmg * (100 - minOf(100, pct)) / 100; after = maxOf(0, after - flat)
                reduced = dmg - after; dmg = after
            }
            var i = 0
            while (i < t.statuses.size && dmg > 0) {
                val d = t.statuses[i]
                if (d.kind != StatusKind.Defense) { i++; continue }
                val take = minOf(d.amount, dmg); d.amount -= take; dmg -= take; absorbed += take
                if (d.amount <= 0) { t.statuses.removeAt(i) } else i++
            }
        }
        t.stamina -= dmg
        log(ev, s, "damage", src?.side ?: -1, src?.slot ?: -1, t.side, t.slot, dmg, "$tag|raw $amount" + (if (raw != amount) " weakened " + (amount - raw) else "") + (if (reduced > 0) " reduce $reduced" else "") + (if (absorbed > 0) " defense $absorbed" else "") + (if (piercing) " piercing" else "") + (if (affliction) " affliction" else ""))
        if (t.stamina <= 0) onDeath(s, t, ev) else checkPassive(s, t, ev)
    }
    private fun onDeath(s: MatchState, t: Fighter, ev: MutableList<MatchEvent>?) {
        t.stamina = 0; t.statuses.clear(); log(ev, s, "out", t.side, t.slot, text = t.hero.id)
        for (sd in 0 until 2)
            for (f in s.sides[sd].fighters) {
                if (f == null) continue
                var i = f.statuses.size - 1
                while (i >= 0) {
                    val st = f.statuses[i]
                    if ((st.kind == StatusKind.Action || st.control) && st.srcSide == t.side && st.srcSlot == t.slot) { f.statuses.removeAt(i); log(ev, s, "contact", t.side, t.slot, sd, f.slot, 0, "$st|${st.skill}|caster out") }
                    i--
                }
            }
    }
    /** The hero's passive fires once when its trigger holds ("hp:50": the stamina at 50 or less; "uses:pNNN.sK:N"); its effects land on the hero. */
    private fun checkPassive(s: MatchState, f: Fighter, ev: MutableList<MatchEvent>?) {
        val p = f.hero.passive; if (p == null || f.passiveOn || !f.alive || p.trigger.isNullOrEmpty()) return
        val tr = p.trigger!!.split(':'); var fire = false
        if (tr[0] == "hp") fire = f.stamina <= tr[1].toInt()
        else if (tr[0] == "uses") fire = f.uses(tr[1]) >= (if (tr.size > 2) tr[2].toInt() else 1)
        if (!fire) return
        f.passiveOn = true
        log(ev, s, "passive", f.side, f.slot, f.side, f.slot, 0, p.id + "|" + p.name)
        val self = ArrayList<Fighter>(1); self.add(f)
        for (i in p.effects.indices) applyEffect(s, f, p, p.effects[i], i, self, ev)
    }

    /** Is a status shown to [viewerSide]: its own, not invisible, or revealed (Око хвилі on a living hero of the viewer). */
    fun visible(s: MatchState, viewerSide: Int, st: Status): Boolean {
        if (!st.invisible || st.srcSide == viewerSide) return true
        for (f in s.sides[viewerSide].fighters) if (f != null && f.alive && f.has(StatusKind.Reveal)) return true
        return false
    }

    /** A short text of the state for logs and cases. */
    fun describe(s: MatchState): String {
        val sb = StringBuilder()
        sb.append("T${s.turn} to move s${s.sideToMove}" + (if (s.finished) " finished winner ${s.winner}" else "") + "\n")
        for (sd in 0 until 2) {
            val side = s.sides[sd]
            sb.append("  s$sd pool ${poolText(side.pool)}\n")
            for (f in side.fighters) {
                if (f == null) continue
                sb.append("    f${f.slot} ${f.hero.id} st ${f.stamina} cd ${f.cooldown[0]}${f.cooldown[1]}${f.cooldown[2]}${f.cooldown[3]}" + (if (f.passiveOn) " passive" else ""))
                for ((k, v) in f.uses) sb.append(" $k:$v")
                for (st in f.statuses) sb.append(" [$st" + (if (st.invisible) " (invisible)" else "") + "]")
                sb.append('\n')
            }
        }
        return sb.toString()
    }
}
