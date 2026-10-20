package com.arenacity.arena

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The json cases of the client (unity/Tests/ArenaCases, copied by tools/arena/sync_skills.sh): a hand-made state → one
 * turn → what must be true afterwards. A port of the client's Cases.cs: the same reading and the same checks, so
 * 145/145 here means the Kotlin engine and the C# engine agree on every rule the cases cover.
 * Statuses in the state are «applied last turn» unless "appliedTurn" is given; "start": true builds the state with
 * Engine.newMatch instead; "thenTurns" plays that many (advance + empty turn) pairs after the main turn.
 */
object CaseRunner {
    class Outcome(val ok: Boolean, val log: String)

    fun run(json: String, lib: SkillLibrary): Outcome {
        val c = Js.obj(Js.parse(json))
        val name = Js.str(c, "name", "?")!!
        val sb = StringBuilder(); val why = StringBuilder()
        val ev = ArrayList<MatchEvent>()
        val s: MatchState = try { if (Js.bool(c, "start")) start(c, lib, ev) else build(c, lib) }
        catch (e: Exception) { return Outcome(false, "FAIL: $name\n  cannot build the state: ${e.message}\n") }
        val t = Turn()
        for (xo in Js.list(c, "exchanges")) { val xd = Js.obj(xo); val x = Exchange(); x.get = Js.int(xd, "get"); val give = Js.ints(xd, "give"); if (give != null) { var i = 0; while (i < Colours.N && i < give.size) { x.give[i] = give[i]; i++ } }; t.exchanges.add(x) }
        var autoPay = false
        for (uo in Js.list(c, "queue")) {
            val ud = Js.obj(uo); val u = SkillUse(Js.int(ud, "slot"), Js.int(ud, "skill"), Js.int(ud, "target", -1))
            val pay = Js.ints(ud, "pay"); if (pay != null && pay.size == Colours.N) u.pay = pay else autoPay = true
            t.queue.add(u)
        }
        var err: String? = null
        if (autoPay) err = Engine.autoPay(s, s.sideToMove, t)
        if (err == null) err = Engine.resolve(s, t, ev)
        val then = Js.int(c, "thenTurns")
        var i = 0
        while (i < then && err == null && !s.finished) { Engine.advance(s, ev); Engine.resolve(s, Turn(), ev); i++ }
        val x0 = Js.get(c, "expect") as? JsonObject ?: JsonObject(emptyMap())
        var ok = true
        val wantErr = Js.str(x0, "error")
        if (wantErr == null && err != null) { ok = false; why.append(" error «$err»;") }
        if (wantErr != null && (err == null || !err.contains(wantErr))) { ok = false; why.append(" expected error «$wantErr», got «$err»;") }
        (Js.get(x0, "stamina") as? JsonArray)?.let { stam ->
            var sd = 0
            while (sd < 2 && sd < stam.size) {
                val row = Js.arr(stam[sd])
                var j = 0
                while (j < 3 && j < row.size) { val want = Js.toInt(row[j]); val f = s.sides[sd].fighters[j]; val got = f?.stamina ?: -1; if (got != want) { ok = false; why.append(" stamina s${sd}f$j $got != $want;") }; j++ }
                sd++
            }
        }
        (Js.get(x0, "pool") as? JsonArray)?.let { pools ->
            var sd = 0
            while (sd < 2 && sd < pools.size) {
                val row = Js.arr(pools[sd])
                var j = 0
                while (j < Colours.N && j < row.size) { val want = Js.toInt(row[j]); if (s.sides[sd].pool[j] != want) { ok = false; why.append(" pool s$sd ${Colours.NAMES[j]} ${s.sides[sd].pool[j]} != $want;") }; j++ }
                sd++
            }
        }
        (Js.get(x0, "cooldown") as? JsonArray)?.let { cds ->
            var sd = 0
            while (sd < 2 && sd < cds.size) {
                val rows = Js.arr(cds[sd])
                var j = 0
                while (j < 3 && j < rows.size) {
                    val row = Js.arr(rows[j]); val f = s.sides[sd].fighters[j]!!
                    var k = 0
                    while (k < 4 && k < row.size) { val want = Js.toInt(row[k]); if (f.cooldown[k] != want) { ok = false; why.append(" cooldown s${sd}f${j}k$k ${f.cooldown[k]} != $want;") }; k++ }
                    j++
                }
                sd++
            }
        }
        (Js.get(x0, "passive") as? JsonArray)?.let { ps ->
            var sd = 0
            while (sd < 2 && sd < ps.size) {
                val row = Js.arr(ps[sd])
                var j = 0
                while (j < 3 && j < row.size) { val want = Js.isBool(row[j]) && (row[j] as JsonPrimitive).content == "true"; val f = s.sides[sd].fighters[j]; if (f != null && f.passiveOn != want) { ok = false; why.append(" passive s${sd}f$j ${f.passiveOn} != $want;") }; j++ }
                sd++
            }
        }
        (Js.get(x0, "uses") as? JsonArray)?.let { us ->
            var sd = 0
            while (sd < 2 && sd < us.size) {
                val row = Js.arr(us[sd])
                var j = 0
                while (j < 3 && j < row.size) {
                    val f = s.sides[sd].fighters[j]; val d = row[j] as? JsonObject
                    if (f != null && d != null) for ((key, v) in d) { val want = if (Js.isNum(v)) Js.num(v).toInt() else 0; if (f.uses(key) != want) { ok = false; why.append(" uses s${sd}f$j $key ${f.uses(key)} != $want;") } }
                    j++
                }
                sd++
            }
        }
        ok = checkStatuses(s, x0, "statuses", true, ok, why)
        ok = checkStatuses(s, x0, "noStatuses", false, ok, why)
        for (want in Js.strs(x0, "events")) {
            var seen = false; for (e in ev) if (e.toString().contains(want)) { seen = true; break }
            if (!seen) { ok = false; why.append(" no event «$want»;") }
        }
        for (want in Js.strs(x0, "noEvents"))
            for (e in ev) if (e.toString().contains(want)) { ok = false; why.append(" unexpected event «$want»;"); break }
        Js.get(x0, "finished")?.let { fin -> if (Js.isBool(fin)) { val f = (fin as JsonPrimitive).content == "true"; if (s.finished != f) { ok = false; why.append(" finished ${s.finished} != $f;") } } }
        Js.get(x0, "winner")?.let { w -> if (Js.isNum(w)) { val want = Js.num(w).toInt(); if (s.winner != want) { ok = false; why.append(" winner ${s.winner} != $want;") } } }
        sb.append("${if (ok) "PASS" else "FAIL"}: $name$why\n")
        sb.append("  turn: $t → ${err ?: "ok"}\n")
        for (e in ev) if (e.kind != "turn") sb.append("  ").append(e).append('\n')
        sb.append("  ").append(Engine.describe(s).replace("\n", "\n  ").trimEnd()).append('\n')
        return Outcome(ok, sb.toString())
    }

    private fun checkStatuses(s: MatchState, x: JsonObject, key: String, must: Boolean, okIn: Boolean, why: StringBuilder): Boolean {
        var ok = okIn
        val list = Js.get(x, key) as? JsonArray ?: return ok
        var n = 0
        while (n < list.size && n < 6) {
            val f = s.sides[n / 3].fighters[n % 3]
            if (f != null)
                for (w in Js.arr(list[n])) {
                    val want = if (Js.isStr(w)) (w as JsonPrimitive).content else ""; if (want == "") continue
                    var seen = false; for (st in f.statuses) if (st.toString().contains(want)) { seen = true; break }
                    if (seen != must) { ok = false; why.append(if (must) " s${n / 3}f${n % 3} lacks «$want»;" else " s${n / 3}f${n % 3} still has «$want»;") }
                }
            n++
        }
        return ok
    }

    private fun start(c: JsonObject, lib: SkillLibrary, ev: MutableList<MatchEvent>): MatchState {
        val sides = Js.list(c, "sides"); val picks = Array<MutableList<HeroPick>>(2) { ArrayList() }
        for (sd in 0 until 2) {
            val d = if (sd < sides.size) Js.obj(sides[sd]) else JsonObject(emptyMap())
            for (fo in Js.list(d, "fighters")) picks[sd].add(HeroPick(Js.str(Js.obj(fo), "id") ?: "", 1))
        }
        return Engine.newMatch(lib, picks[0], picks[1], Js.int(c, "seed", 1).toULong(), ev, Js.int(c, "first", 0))
    }

    private fun build(c: JsonObject, lib: SkillLibrary): MatchState {
        val s = MatchState(Rng(Js.int(c, "seed", 1).toULong()), lib)
        s.turn = Js.int(c, "turn", 1); s.sideToMove = Js.int(c, "sideToMove", 0); s.first = Js.int(c, "first", 0)
        val sides = Js.list(c, "sides")
        for (sd in 0 until 2) {
            val side = SideState(); s.sides[sd] = side
            val d = if (sd < sides.size) Js.obj(sides[sd]) else JsonObject(emptyMap())
            val pool = Js.ints(d, "pool"); if (pool != null) { var i = 0; while (i < Colours.N && i < pool.size) { side.pool[i] = pool[i]; i++ } }
            val fs = Js.list(d, "fighters")
            var i = 0
            while (i < 3 && i < fs.size) {
                val fd = Js.obj(fs[i])
                val h = lib.hero(Js.str(fd, "id")) ?: throw IllegalArgumentException("unknown hero ${Js.str(fd, "id")}")
                val f = Fighter(h); f.slot = i; f.side = sd; f.kit = h.kit(); f.stamina = Js.int(fd, "stamina", lib.rules.stamina); f.passiveOn = Js.bool(fd, "passiveOn")
                val cd = Js.ints(fd, "cooldown"); if (cd != null) { var k = 0; while (k < 4 && k < cd.size) { f.cooldown[k] = cd[k]; k++ } }
                val acd = Js.ints(fd, "altCooldown"); if (acd != null) { var k = 0; while (k < 4 && k < acd.size) { f.altCooldown[k] = acd[k]; k++ } }
                (Js.get(fd, "uses") as? JsonObject)?.let { ud -> for ((key, v) in ud) f.uses[key] = if (Js.isNum(v)) Js.num(v).toInt() else 0 }
                for (so in Js.list(fd, "statuses")) {
                    val sdct = Js.obj(so)
                    val st = Status()
                    st.kind = StatusKind.valueOf(Js.str(sdct, "kind")!!); st.amount = Js.int(sdct, "amount"); st.turnsLeft = Js.int(sdct, "turns", 1)
                    st.appliedTurn = Js.int(sdct, "appliedTurn", s.turn - 1); st.srcSide = Js.int(sdct, "srcSide", 1 - sd); st.srcSlot = Js.int(sdct, "srcSlot"); st.param = Js.int(sdct, "param", -1)
                    st.effectIndex = Js.int(sdct, "effectIndex", -1); st.skill = Js.str(sdct, "skill", "case"); st.altSkill = Js.str(sdct, "altSkill")
                    st.permanent = Js.bool(sdct, "permanent"); st.unpierceable = Js.bool(sdct, "unpierceable"); st.percent = Js.bool(sdct, "percent"); st.invisible = Js.bool(sdct, "invisible")
                    st.unignorable = Js.bool(sdct, "unignorable"); st.control = Js.bool(sdct, "control"); st.stacks = Js.bool(sdct, "stacks")
                    for (cl in Js.strs(sdct, "classes")) st.classes = st.classes or SkillClasses.parse(cl).first
                    f.statuses.add(st)
                }
                side.fighters[i] = f
                i++
            }
        }
        return s
    }
}
