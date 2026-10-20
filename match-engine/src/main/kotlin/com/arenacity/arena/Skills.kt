package com.arenacity.arena

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The effect kinds of a skill (arena_skills.json «effects[].kind»), one to one with the original's terms. */
enum class EffectKind {
    Damage, Heal, Reduce, Defense, Invuln, Stun, NoReduce, NoInvuln, Mark, Weaken, CostMod, Regen, IgnoreHarm, Reveal, Counter, Reflect,
    RemoveEnergy, ExtraIncome, Trap, Used,
}
/** Who an effect lands on: the skill's target(s), the caster, all allies, all enemies (the targetable ones), every enemy but the target, one random other enemy. */
enum class EffectTo { Target, Self, Allies, Enemies, OtherEnemies, RandomOtherEnemy }

/** A condition of a skill, an effect, a bonus or a cost change. */
enum class CondKind {
    None,        // always holds (for ignoresInvuln: never)
    Always,
    Passive,     // the caster's passive has fired
    During,      // the caster carries a status of skill [skill]
    After,       // the caster used skill [skill] last own turn (its Used marker)
    TargetHas,   // the recipient carries a status of skill [skill]
    Uses,        // the caster used skill [skill] at least [n] times
}
class Cond(val kind: CondKind, val skill: String? = null, val n: Int = 0) {
    companion object {
        val NONE = Cond(CondKind.None)
        /** "" | "always" | "passive" | "during:pNNN.sK" | "after:pNNN.sK" | "target_has:pNNN.sK" | "uses:pNNN.sK:N". */
        fun parse(s: String?): Cond {
            if (s.isNullOrEmpty()) return NONE
            if (s == "always") return Cond(CondKind.Always)
            if (s == "passive") return Cond(CondKind.Passive)
            val p = s.split(':')
            return when (p[0]) {
                "during" -> Cond(CondKind.During, p[1]); "after" -> Cond(CondKind.After, p[1]); "target_has" -> Cond(CondKind.TargetHas, p[1])
                "uses" -> Cond(CondKind.Uses, p[1], if (p.size > 2) p[2].toInt() else 1)
                else -> throw IllegalArgumentException("cond '$s'")
            }
        }
    }
    override fun toString(): String = when (kind) { CondKind.None -> ""; CondKind.Always, CondKind.Passive -> kind.name.lowercase(); else -> "$kind:$skill" + (if (n > 0) ":$n" else "") }
}
/** What scales a bonus: nothing, the caster's uses of a skill, the Mark stacks of a skill on the recipient. */
enum class PerKind { None, Uses, Stacks }
class Bonus { var amount = 0; var cond = Cond.NONE; var per = PerKind.None; var perSkill: String? = null }
/** A duration change: +turns (or = turns when [replace]) while the condition holds. */
class Extend { var turns = 0; var replace = false; var cond = Cond.NONE }
/** A cost change of a skill: +any (negative = cheaper) scaled by the uses of [perSkill] when per is set, or a whole other cost ([cost] / [any]) as a variant. */
class CostMod { var any = 0; var cost: IntArray? = null; var replace = false; var cond = Cond.NONE; var per = PerKind.None; var perSkill: String? = null }

class Effect {
    var kind = EffectKind.Damage; var amount = 0; var turns = 0; var param = 0; var to = EffectTo.Target
    var piercing = false; var affliction = false; var unpierceable = false; var percent = false; var ignoreInvuln = false; var invisible = false
    var unignorable = false; var permanent = false; var stacks = false; var action = false; var control = false
    /** The type filter of a Stun / Invuln / Weaken / CostMod / Counter / Reflect (NONE = every type); RemoveEnergy: the colour mask (bits 0..3; 0 = any). */
    var classes = SkillClass.NONE
    var cond = Cond.NONE
    val bonuses: MutableList<Bonus> = ArrayList(); val extends: MutableList<Extend> = ArrayList()
    val isDamage: Boolean get() = kind == EffectKind.Damage
    override fun toString(): String = "$kind $amount" + (if (turns > 0) "×$turns" else "") + "→$to"
}

enum class SkillRole { Skill, Alt, Passive }

/**
 * One skill: the id «pNNN.sK» (alternates «pNNN.aK», passives «pNNN.pK»), the kit slot it occupies, cost, cooldown,
 * classes, target kind, conditions and effects. Costs: colour counts + any.
 */
class SkillDef(val hero: HeroDef) {
    var id = ""; var template = ""; var icon = ""; var name: String? = null; var proto = ""; var text = ""; var costText = ""
    var slot = -1; var cooldown = 0
    var role = SkillRole.Skill
    var cost = IntArray(Colours.N); var any = 0
    var classes = SkillClass.NONE; var persistencePartial = false
    var target = TargetKind.ENEMY; val effects: MutableList<Effect> = ArrayList()
    var requires: String? = null; var notOnAffected = false; var uncounterable = false; var unreflectable = false; var invisible = false; var maxUses = 0
    var ignoresInvuln = Cond.NONE
    var alt: String? = null; var altTurns = 0; var trigger: String? = null
    val costMods: MutableList<CostMod> = ArrayList()
    val names: MutableMap<String, String> = HashMap(); val texts: MutableMap<String, String> = HashMap()
    fun name(lang: String?): String = (if (lang != null) names[lang]?.takeIf { it.isNotEmpty() } else null) ?: name ?: id
    fun text(lang: String?): String = (if (lang != null) texts[lang]?.takeIf { it.isNotEmpty() } else null) ?: text
    val totalCost: Int get() { var n = any; for (c in 0 until Colours.N) n += cost[c]; return n }
    val type: Int get() = classes and SkillClass.TYPE_MASK
    val isInvulnSlot: Boolean get() = slot == 3 && role == SkillRole.Skill
    val harmful: Boolean get() = target == TargetKind.ENEMY || target == TargetKind.ENEMIES
    val dealsDamage: Boolean get() { for (e in effects) if (e.kind == EffectKind.Damage && e.to != EffectTo.Self) return true; return false }
    val classText: String get() = SkillClasses.ALL.filter { (classes and it.second) != 0 }.joinToString(", ") { it.first + (if (persistencePartial && it.second == SkillClass.INSTANT) "*" else "") }
    override fun toString(): String = "$id $name [$costText] cd$cooldown $target $classText"
}

class HeroDef {
    var id = ""; var name: String? = null; var nameUk: String? = null; var power: String? = null; var proto = ""; var number = 0; var colour = Colour.SPEED
    /** Every skill of the hero: the four of the kit, the alternates, the passive. */
    val skills: MutableList<SkillDef> = ArrayList()
    fun skill(id: String?): SkillDef? { for (s in skills) if (s.id == id) return s; return null }
    val passive: SkillDef? get() { for (s in skills) if (s.role == SkillRole.Passive) return s; return null }
    /** The base kit: the four skills of role Skill in their slots. */
    fun kit(): Array<SkillDef?> {
        val kit = arrayOfNulls<SkillDef>(4)
        for (s in skills) if (s.role == SkillRole.Skill && s.slot in 0..3) kit[s.slot] = s
        return kit
    }
    override fun toString(): String = "$id $name ← $proto"
}

/** The rules of arena_skills.json «rules»: stamina, the turn cap, the exchange rate, the first mover's income on turn 1. */
class Rules { var stamina = 100; var maxTurns = 40; var exchange = 5; var firstTurnIncome = 1 }

/** The 11 heroes with their kits (arena_skills.json). */
class SkillLibrary {
    val rules = Rules()
    val heroes: MutableList<HeroDef> = ArrayList()
    private val byId = HashMap<String, HeroDef>()
    private val skillById = HashMap<String, SkillDef>()
    fun hero(id: String?): HeroDef? = byId[id]
    fun skill(id: String?): SkillDef? = if (id != null) skillById[id] else null
    val skillCount: Int get() = skillById.size

    companion object {
        fun parse(json: String): SkillLibrary {
            val root = Js.obj(Js.parse(json))
            val lib = SkillLibrary()
            val r = Js.get(root, "rules") as? JsonObject
            if (r != null) {
                lib.rules.stamina = Js.int(r, "stamina", 100); lib.rules.maxTurns = Js.int(r, "maxTurns", 40); lib.rules.exchange = Js.int(r, "exchange", 5)
                lib.rules.firstTurnIncome = Js.int(r, "firstTurnIncome", 1)
            }
            for (ho in Js.list(root, "heroes")) {
                val hd = Js.obj(ho)
                val h = HeroDef()
                h.id = Js.str(hd, "id") ?: ""; h.name = Js.str(hd, "name"); h.nameUk = Js.str(hd, "nameUk"); h.power = Js.str(hd, "power"); h.proto = Js.str(hd, "proto", "")!!; h.number = Js.int(hd, "number")
                val col = Colours.parse(Js.str(hd, "colour", "speed")!!); h.colour = Colour.values()[if (col < 0) 0 else col]
                for (so in Js.list(hd, "skills")) {
                    val sd = Js.obj(so)
                    val s = SkillDef(h)
                    s.id = Js.str(sd, "id") ?: ""; s.template = Js.str(sd, "template", "")!!; s.icon = Js.str(sd, "icon", "")!!; s.name = Js.str(sd, "name"); s.proto = Js.str(sd, "proto", "")!!; s.text = Js.str(sd, "text", "")!!
                    s.slot = Js.int(sd, "slot", -1); s.cooldown = Js.int(sd, "cooldown"); s.costText = Js.str(sd, "cost", "")!!
                    s.requires = Js.str(sd, "requires"); s.notOnAffected = Js.bool(sd, "notOnAffected"); s.maxUses = Js.int(sd, "maxUses")
                    s.uncounterable = Js.bool(sd, "uncounterable"); s.unreflectable = Js.bool(sd, "unreflectable"); s.invisible = Js.bool(sd, "invisible")
                    s.ignoresInvuln = Cond.parse(Js.str(sd, "ignoresInvuln", "")); s.alt = Js.str(sd, "alt"); s.altTurns = Js.int(sd, "altTurns"); s.trigger = Js.str(sd, "trigger")
                    for ((key, v) in sd) {
                        if (!(v is JsonPrimitive && v.isString)) continue
                        if (key.startsWith("name_")) s.names[key.substring(5)] = v.content else if (key.startsWith("text_")) s.texts[key.substring(5)] = v.content
                    }
                    s.role = when (val k = Js.str(sd, "role", "skill")) { "skill" -> SkillRole.Skill; "alt" -> SkillRole.Alt; "passive" -> SkillRole.Passive; else -> throw IllegalArgumentException("${s.id}: role '$k'") }
                    s.any = parseCost(s.costText, s.cost, s.id)
                    for (c in Js.strs(sd, "classes")) { val (bit, partial) = SkillClasses.parse(c); s.classes = s.classes or bit; if (partial) s.persistencePartial = true }
                    s.target = when (val t = Js.str(sd, "target", "enemy")) {
                        "enemy" -> TargetKind.ENEMY; "ally" -> TargetKind.ALLY; "self" -> TargetKind.SELF; "enemies" -> TargetKind.ENEMIES; "allies" -> TargetKind.ALLIES; "none" -> TargetKind.NONE
                        else -> throw IllegalArgumentException("${s.id}: target '$t'")
                    }
                    for (mo in Js.list(sd, "costMods")) {
                        val md = Js.obj(mo)
                        val m = CostMod(); m.any = Js.int(md, "any"); m.cond = Cond.parse(Js.str(md, "cond", ""))
                        val ct = Js.str(md, "cost"); if (ct != null) { val cost = IntArray(Colours.N); m.any = parseCost(ct, cost, s.id); m.cost = cost; m.replace = true }
                        val (per, perSkill) = parsePer(Js.str(md, "per", "")!!); m.per = per; m.perSkill = perSkill
                        s.costMods.add(m)
                    }
                    for (eo in Js.list(sd, "effects")) {
                        val ed = Js.obj(eo)
                        val e = Effect(); e.amount = Js.int(ed, "amount"); e.turns = Js.int(ed, "turns"); e.param = Js.int(ed, "param"); e.cond = Cond.parse(Js.str(ed, "cond", ""))
                        e.kind = when (val k = Js.str(ed, "kind")) {
                            "damage" -> EffectKind.Damage; "heal" -> EffectKind.Heal; "reduce" -> EffectKind.Reduce; "defense" -> EffectKind.Defense; "invuln" -> EffectKind.Invuln
                            "stun" -> EffectKind.Stun; "no_reduce" -> EffectKind.NoReduce; "no_invuln" -> EffectKind.NoInvuln; "mark" -> EffectKind.Mark; "weaken" -> EffectKind.Weaken
                            "cost_mod" -> EffectKind.CostMod; "regen" -> EffectKind.Regen; "ignore_harm" -> EffectKind.IgnoreHarm; "reveal" -> EffectKind.Reveal
                            "counter" -> EffectKind.Counter; "reflect" -> EffectKind.Reflect; "remove_energy" -> EffectKind.RemoveEnergy; "extra_income" -> EffectKind.ExtraIncome
                            "trap" -> EffectKind.Trap; "used" -> EffectKind.Used
                            else -> throw IllegalArgumentException("${s.id}: effect kind '$k'")
                        }
                        e.to = when (val t = Js.str(ed, "to", "target")) {
                            "target" -> EffectTo.Target; "self" -> EffectTo.Self; "allies" -> EffectTo.Allies; "enemies" -> EffectTo.Enemies; "other_enemies" -> EffectTo.OtherEnemies
                            "random_other_enemy" -> EffectTo.RandomOtherEnemy; else -> throw IllegalArgumentException("${s.id}: to '$t'")
                        }
                        for (f in Js.strs(ed, "flags")) when (f) {
                            "piercing" -> e.piercing = true; "affliction" -> e.affliction = true; "unpierceable" -> e.unpierceable = true
                            "percent" -> e.percent = true; "ignore_invuln" -> e.ignoreInvuln = true; "invisible" -> e.invisible = true
                            "unignorable" -> e.unignorable = true; "permanent" -> e.permanent = true; "stacks" -> e.stacks = true
                            "action" -> e.action = true; "control" -> e.control = true
                            else -> throw IllegalArgumentException("${s.id}: flag '$f'")
                        }
                        for (c in Js.strs(ed, "classes")) e.classes = e.classes or SkillClasses.parse(c).first
                        if (e.kind == EffectKind.RemoveEnergy) for (c in Js.strs(ed, "colours")) { val ci = Colours.parse(c); if (ci < 0) throw IllegalArgumentException("${s.id}: colour '$c'"); e.classes = e.classes or (1 shl ci) }
                        for (bo in Js.list(ed, "bonus")) {
                            val bd = Js.obj(bo)
                            val b = Bonus(); b.amount = Js.int(bd, "amount"); b.cond = Cond.parse(Js.str(bd, "cond", ""))
                            val (per, perSkill) = parsePer(Js.str(bd, "per", "")!!); b.per = per; b.perSkill = perSkill
                            e.bonuses.add(b)
                        }
                        for (xo in Js.list(ed, "extend")) {
                            val xd = Js.obj(xo)
                            val x = Extend(); x.turns = Js.int(xd, "turns"); x.replace = Js.bool(xd, "replace"); x.cond = Cond.parse(Js.str(xd, "cond", "")); e.extends.add(x)
                        }
                        if (s.invisible) e.invisible = true
                        s.effects.add(e)
                    }
                    h.skills.add(s); lib.skillById[s.id] = s
                }
                lib.heroes.add(h); lib.byId[h.id] = h
            }
            return lib
        }
        private fun parseCost(text: String, cost: IntArray, id: String): Int {
            var any = 0
            for (c in text) { val ci = Colours.LETTERS.indexOf(c); if (ci >= 0) cost[ci]++ else if (c == '.') any++ else throw IllegalArgumentException("$id: cost '$text'") }
            return any
        }
        /** "" | "uses:pNNN.sK" | "stacks:pNNN.sK". */
        private fun parsePer(s: String): Pair<PerKind, String?> {
            if (s.isEmpty()) return PerKind.None to null
            val p = s.split(':'); val skill = if (p.size > 1) p[1] else null
            val per = when (p[0]) { "uses" -> PerKind.Uses; "stacks" -> PerKind.Stacks; else -> throw IllegalArgumentException("per '$s'") }
            return per to skill
        }
        /** The cost as letters: "TN." etc. */
        fun costText(cost: IntArray, any: Int): String {
            val sb = StringBuilder()
            for (c in 0 until Colours.N) for (i in 0 until cost[c]) sb.append(Colours.LETTERS[c])
            for (i in 0 until any) sb.append('.')
            return if (sb.isEmpty()) "—" else sb.toString()
        }
    }
}

/**
 * The port's invariants: 11 heroes, four kit skills each; slot 3 = invulnerable 1 turn for 1 any, cooldown 4; slot 0 deals
 * damage (or marks a target); every cost ≤ 3; an alternate is referenced by a kit skill and replaces that skill's slot; a
 * passive has a trigger; conditions, requirements and bonuses refer to existing skills.
 */
object Validator {
    fun check(lib: SkillLibrary): List<String> {
        val errs = ArrayList<String>()
        if (lib.heroes.size != 11) errs.add("${lib.heroes.size} heroes, expected 11")
        for (h in lib.heroes) {
            val kit = h.kit()
            for (k in 0 until 4) if (kit[k] == null) errs.add("${h.id}: slot $k empty")
            val kitCount = h.skills.count { it.role == SkillRole.Skill }
            if (kitCount != 4) errs.add("${h.id}: $kitCount kit skills, expected 4")
            val k0 = kit[0]
            if (k0 != null && !k0.dealsDamage) { val marks = k0.effects.any { it.kind == EffectKind.Mark && it.to == EffectTo.Target }; if (!marks) errs.add("${k0.id}: slot 0 must deal damage") }
            if (kit.none { it != null && it.dealsDamage }) errs.add("${h.id}: no damage skill")
            val inv = kit[3]
            if (inv != null) {
                val ok = inv.cooldown == 4 && inv.any == 1 && inv.totalCost == 1 && inv.target == TargetKind.SELF && inv.effects.size == 1 && inv.effects[0].kind == EffectKind.Invuln && inv.effects[0].turns == 1 && inv.effects[0].classes == SkillClass.NONE && inv.effects[0].to == EffectTo.Self
                if (!ok) errs.add("${inv.id}: slot 3 must be «invulnerable 1 turn» for 1 any with cooldown 4")
            }
            for (s in h.skills) {
                if (s.totalCost > 3) errs.add("${s.id}: cost ${s.totalCost} > 3")
                if (s.cooldown < 0 || s.cooldown > 6) errs.add("${s.id}: cooldown ${s.cooldown}")
                if (s.role != SkillRole.Passive && (s.classes and SkillClass.TYPE_MASK) == 0) errs.add("${s.id}: no type class")
                if (s.role == SkillRole.Passive && s.trigger.isNullOrEmpty()) errs.add("${s.id}: passive without a trigger")
                if (s.role == SkillRole.Alt) { var referenced = false; for (o in h.skills) if (o.alt == s.id) { referenced = true; if (o.slot != s.slot) errs.add("${s.id}: alternate slot ${s.slot} != ${o.id}'s slot ${o.slot}") }; if (!referenced) errs.add("${s.id}: alternate referenced by no kit skill") }
                if (s.alt != null && (lib.skill(s.alt) == null || lib.skill(s.alt)!!.role != SkillRole.Alt || s.altTurns <= 0)) errs.add("${s.id}: alt '${s.alt}' missing or without altTurns")
                if (s.requires != null && lib.skill(s.requires) == null) errs.add("${s.id}: requires unknown '${s.requires}'")
                if (s.effects.isEmpty()) errs.add("${s.id}: no effects")
                ref(lib, errs, s.id, s.ignoresInvuln)
                for (m in s.costMods) { ref(lib, errs, s.id, m.cond); if (m.per != PerKind.None && lib.skill(m.perSkill) == null) errs.add("${s.id}: cost per unknown '${m.perSkill}'") }
                for (e in s.effects) {
                    ref(lib, errs, s.id, e.cond)
                    for (b in e.bonuses) { ref(lib, errs, s.id, b.cond); if (b.per != PerKind.None && lib.skill(b.perSkill) == null) errs.add("${s.id}: bonus per unknown '${b.perSkill}'") }
                    for (x in e.extends) ref(lib, errs, s.id, x.cond)
                    if ((e.kind == EffectKind.Damage || e.kind == EffectKind.Heal) && e.amount <= 0) errs.add("${s.id}: ${e.kind} amount ${e.amount}")
                    if (e.to == EffectTo.Target && s.target == TargetKind.NONE) errs.add("${s.id}: ${e.kind} aimed at the target of a targetless skill")
                    val timed = e.kind == EffectKind.Reduce || e.kind == EffectKind.Invuln || e.kind == EffectKind.Stun || e.kind == EffectKind.NoReduce || e.kind == EffectKind.NoInvuln || e.kind == EffectKind.Mark ||
                        e.kind == EffectKind.Weaken || e.kind == EffectKind.CostMod || e.kind == EffectKind.IgnoreHarm || e.kind == EffectKind.Reveal || e.kind == EffectKind.Trap || e.kind == EffectKind.Used
                    if (e.turns <= 0 && !e.permanent && timed) errs.add("${s.id}: ${e.kind} without a duration")
                    if (e.action && e.kind != EffectKind.Damage) errs.add("${s.id}: action flag on ${e.kind}")
                }
            }
        }
        return errs
    }
    private fun ref(lib: SkillLibrary, errs: MutableList<String>, id: String, c: Cond) {
        if (c.skill != null && lib.skill(c.skill) == null) errs.add("$id: condition refers to unknown '${c.skill}'")
    }
}

/** The library the server ships: match-engine/src/main/resources/arena/arena_skills.json, copied from the mobile repo by tools/arena/sync_skills.sh. */
object ArenaSkills {
    const val RESOURCE = "/arena/arena_skills.json"
    val library: SkillLibrary by lazy { SkillLibrary.parse(text()) }
    fun text(): String = ArenaSkills::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("$RESOURCE missing: run tools/arena/sync_skills.sh")
}
