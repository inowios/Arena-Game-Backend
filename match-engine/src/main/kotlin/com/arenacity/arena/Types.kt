package com.arenacity.arena

/**
 * «Арена v4» — the data of a 3-on-3 hero match: a line-for-line port of the client's C# engine
 * (Arena-Game-Mobile/unity/Assets/Scripts/Cards/Arena/Types.cs; docs/claude/ARENA-MATCH-V3.md §16), itself an exact port of
 * Naruto-Arena. The server plays the league with it (SmartAi v SmartAi) and will referee PvP; the 145 case files of the
 * client (tools/arena/sync_skills.sh) prove the two engines agree. Energy = the four football qualities, one to one with
 * the four chakras: Швидкість (Taijutsu), Техніка (Ninjutsu), Тактика (Genjutsu), Сила (Bloodline); a cost may also ask for «any».
 */
enum class Colour { SPEED, TECHNIQUE, TACTICS, STRENGTH }

object Colours {
    const val N = 4
    val NAMES = arrayOf("speed", "technique", "tactics", "strength")
    val NAMES_UK = arrayOf("Швидкість", "Техніка", "Тактика", "Сила")
    val PROTO = arrayOf("Tai", "Nin", "Gen", "Blood")
    val LETTERS = charArrayOf('T', 'N', 'G', 'B')
    fun parse(s: String): Int = NAMES.indexOf(s)
}

/** Who a skill is aimed at: one enemy / one ally (the caster included) / the caster / all enemies / all allies / nobody. */
enum class TargetKind { ENEMY, ALLY, SELF, ENEMIES, ALLIES, NONE }

/**
 * The skill classes of the original as bit flags: the type (one of Physical / Chakra / Mental / Affliction), the distance,
 * the persistence (Instant / Action / Control), Unique, Helpful. Statuses use the type bits as filters («stuns physical
 * and mental skills», «invulnerable to non-mental skills»). An Int, as the C# [Flags] enum is.
 */
object SkillClass {
    const val NONE = 0
    const val PHYSICAL = 1; const val CHAKRA = 2; const val MENTAL = 4; const val AFFLICTION = 8
    const val MELEE = 16; const val RANGED = 32; const val INSTANT = 64; const val ACTION = 128; const val CONTROL = 256
    const val UNIQUE = 512; const val HELPFUL = 1024
    const val TYPE_MASK = PHYSICAL or CHAKRA or MENTAL or AFFLICTION
    const val NON_MENTAL = PHYSICAL or CHAKRA or AFFLICTION
    const val NON_AFFLICTION = PHYSICAL or CHAKRA or MENTAL
}

object SkillClasses {
    val ALL: List<Pair<String, Int>> = listOf(
        "Physical" to SkillClass.PHYSICAL, "Chakra" to SkillClass.CHAKRA, "Mental" to SkillClass.MENTAL, "Affliction" to SkillClass.AFFLICTION,
        "Melee" to SkillClass.MELEE, "Ranged" to SkillClass.RANGED, "Instant" to SkillClass.INSTANT, "Action" to SkillClass.ACTION, "Control" to SkillClass.CONTROL,
        "Unique" to SkillClass.UNIQUE, "Helpful" to SkillClass.HELPFUL,
    )
    /** "Physical" → the bit; "Instant*" → the bit with partial true; "non-mental" / "non-affliction" shortcuts. */
    fun parse(text: String): Pair<Int, Boolean> {
        var s = text
        val partial = s.endsWith("*"); if (partial) s = s.substring(0, s.length - 1)
        if (s == "non-mental") return SkillClass.NON_MENTAL to partial
        if (s == "non-affliction") return SkillClass.NON_AFFLICTION to partial
        for ((name, bit) in ALL) if (name.equals(s, ignoreCase = true)) return bit to partial
        throw IllegalArgumentException("skill class '$s'")
    }
    /** The type letters of a filter: P C M A (empty = every type). */
    fun short(c: Int): String {
        val sb = StringBuilder()
        if (c and SkillClass.PHYSICAL != 0) sb.append('P'); if (c and SkillClass.CHAKRA != 0) sb.append('C')
        if (c and SkillClass.MENTAL != 0) sb.append('M'); if (c and SkillClass.AFFLICTION != 0) sb.append('A')
        return sb.toString()
    }
    /** Does a filter (NONE = everything) cover a skill's type. */
    fun covers(filter: Int, skillType: Int): Boolean = (filter and SkillClass.TYPE_MASK) == 0 || (filter and skillType and SkillClass.TYPE_MASK) != 0
}

/**
 * The statuses a fighter can carry. Durations tick at the end of the owner's side's turn (a status applied this turn
 * waits); Action statuses tick on the caster's turn start instead; permanent ones never.
 */
enum class StatusKind {
    Reduce,       // damage reduction: amount points (percent when [percent]); [unpierceable] holds against piercing; never against affliction
    Defense,      // destructible defense: amount absorbs damage before the stamina (not affliction); gone at 0
    Invuln,       // invulnerable: not a target of enemy skills of the [classes] types (NONE = all)
    Stun,         // cannot use skills of the [classes] types (NONE = all); [control] = ends when the caster loses contact
    NoReduce,     // cannot reduce damage (its Reduce statuses are ignored)
    NoInvuln,     // cannot become invulnerable (its Invuln statuses are ignored)
    Mark,         // a marker of its [skill]
    Weaken,       // deals amount less damage with skills of the [classes] types
    CostMod,      // its skills of the [classes] types cost amount more «any» (param 1: only non-harmful skills)
    Action,       // an ongoing skill: effect [effectIndex] of [skill] by the caster hits again at each start of the caster's turn
    Regen,        // heals amount at the start of its own turn
    IgnoreHarm,   // enemy non-damage effects do not stick (unless they say «cannot be ignored»)
    Reveal,       // sees the rival's invisible statuses (a UI flag)
    Counter,      // the first enemy harmful skill of the [classes] types at me is negated
    Reflect,      // the first enemy harmful skill of the [classes] types at me is applied to its caster instead
    Trap,         // Пастка: if I use a harmful skill while it lasts, the trap springs
    Used,         // «one turn after X»: X was used last own turn
    Alt,          // kit slot [param] shows [altSkill] instead of its skill
    ExtraIncome,  // amount more random energy for my side each of my turns
}

/** Not a data class on purpose: the engine finds a status in a list by identity (as the C# List.IndexOf does). */
class Status {
    var kind: StatusKind = StatusKind.Reduce
    var amount = 0; var turnsLeft = 0; var appliedTurn = 0; var srcSide = 0; var srcSlot = 0; var param = -1; var effectIndex = -1
    var permanent = false; var unpierceable = false; var percent = false; var invisible = false; var unignorable = false; var control = false; var stacks = false
    var classes = SkillClass.NONE
    var skill: String? = null; var altSkill: String? = null

    fun clone(): Status {
        val c = Status()
        c.kind = kind; c.amount = amount; c.turnsLeft = turnsLeft; c.appliedTurn = appliedTurn; c.srcSide = srcSide; c.srcSlot = srcSlot; c.param = param; c.effectIndex = effectIndex
        c.permanent = permanent; c.unpierceable = unpierceable; c.percent = percent; c.invisible = invisible; c.unignorable = unignorable; c.control = control; c.stacks = stacks
        c.classes = classes; c.skill = skill; c.altSkill = altSkill
        return c
    }
    /** "Reduce 15×5", "Reduce 10u×5", "Reduce 75%u∞", "Stun PM×1", "Invuln PCA×1", "Mark 10∞", "Action 15×2", "CostMod 1 PM×2". */
    override fun toString(): String {
        val sb = StringBuilder(kind.name)
        if (amount != 0) sb.append(' ').append(amount)
        if (percent) sb.append('%')
        if (unpierceable) sb.append('u')
        val cl = SkillClasses.short(classes); if (cl.isNotEmpty()) sb.append(' ').append(cl)
        if (kind == StatusKind.CostMod && param == 1) sb.append(" nh")
        sb.append(if (permanent) "∞" else "×$turnsLeft")
        return sb.toString()
    }
}

/**
 * A hero on the field: its base kit of four skills, the stamina, the cooldowns per kit slot (the alternate skill of a
 * slot has its own), the statuses, how many times each skill was used, whether the passive has fired.
 */
class Fighter(val hero: HeroDef) {
    var level = 1; var slot = 0; var side = 0
    var kit: Array<SkillDef?> = arrayOfNulls(4)
    var stamina = 100
    /** Turns of the fighter's own side the base skill of this kit slot still rests (0 = ready); [altCooldown] the same for the alternate skill shown in the slot. */
    var cooldown = IntArray(4); var altCooldown = IntArray(4)
    var statuses: MutableList<Status> = ArrayList()
    var uses: MutableMap<String, Int> = HashMap()
    var passiveOn = false
    val alive: Boolean get() = stamina > 0
    val colour: Colour get() = hero.colour
    val id: String get() = hero.id
    fun uses(skillId: String): Int = uses[skillId] ?: 0
    fun has(k: StatusKind): Boolean { for (st in statuses) if (st.kind == k) return true; return false }
    fun find(k: StatusKind): Status? { for (st in statuses) if (st.kind == k) return st; return null }
    /** A status sourced by [skillId] (markers, reductions, stuns…; Trap / Used / Alt do not count as «affected by»). */
    fun hasFrom(skillId: String?): Boolean { for (st in statuses) if (st.skill == skillId && st.kind != StatusKind.Trap && st.kind != StatusKind.Used && st.kind != StatusKind.Alt) return true; return false }
    fun stacks(skillId: String?): Int { var n = 0; for (st in statuses) if (st.skill == skillId && st.kind == StatusKind.Mark) n++; return n }
    /** The skill kit slot [k] shows now: the alternate while an Alt status for the slot lasts, else the base skill. */
    fun current(k: Int): SkillDef? {
        if (k < 0 || k > 3) return null
        for (st in statuses) if (st.kind == StatusKind.Alt && st.param == k && st.altSkill != null) { val a = hero.skill(st.altSkill); if (a != null) return a }
        return kit[k]
    }
    fun isAlt(k: Int): Boolean { for (st in statuses) if (st.kind == StatusKind.Alt && st.param == k) return true; return false }
    fun cooldownOf(k: Int): Int = if (isAlt(k)) altCooldown[k] else cooldown[k]
    val defense: Int get() { var n = 0; for (st in statuses) if (st.kind == StatusKind.Defense) n += st.amount; return n }
    fun clone(): Fighter {
        val f = Fighter(hero)
        f.level = level; f.slot = slot; f.side = side; f.stamina = stamina; f.passiveOn = passiveOn
        f.cooldown = cooldown.copyOf(); f.altCooldown = altCooldown.copyOf(); f.kit = kit
        f.statuses = ArrayList(statuses.size); for (st in statuses) f.statuses.add(st.clone())
        f.uses = HashMap(uses)
        return f
    }
}

class SideState {
    var fighters: Array<Fighter?> = arrayOfNulls(3)
    /** Energy per colour. */
    var pool = IntArray(Colours.N)
    var exchanges = 0; var skillsUsed = 0; var invulnUsed = 0
    val total: Int get() { var n = 0; for (c in 0 until Colours.N) n += pool[c]; return n }
    val aliveCount: Int get() { var n = 0; for (f in fighters) if (f != null && f.alive) n++; return n }
    val staminaTotal: Int get() { var n = 0; for (f in fighters) if (f != null) n += f.stamina; return n }
    fun clone(): SideState {
        val c = SideState()
        c.exchanges = exchanges; c.skillsUsed = skillsUsed; c.invulnUsed = invulnUsed
        for (i in 0 until 3) c.fighters[i] = fighters[i]?.clone()
        c.pool = pool.copyOf()
        return c
    }
}

class MatchState(var rng: Rng, val lib: SkillLibrary) {
    var sides: Array<SideState> = arrayOf(SideState(), SideState())
    /** Turn number from 1; the side that moves on it; the side that moved first. */
    var turn = 1; var sideToMove = 0; var first = 0
    var finished = false; var winner = -1
    val mover: SideState get() = sides[sideToMove]
    val other: SideState get() = sides[1 - sideToMove]
    fun clone(): MatchState {
        val c = MatchState(rng.clone(), lib)
        c.turn = turn; c.sideToMove = sideToMove; c.first = first; c.finished = finished; c.winner = winner
        c.sides = arrayOf(sides[0].clone(), sides[1].clone())
        return c
    }
}

/**
 * One entry of the skill queue: fighter [slot] uses kit slot [skill] at [target] (an index on the side the skill's target
 * kind implies; −1 for self / all / none), paying [pay] energies per colour (the colours of the cost plus the colours
 * chosen for its «any»; null = not decided yet, Engine.autoPay fills it).
 */
class SkillUse(var slot: Int = 0, var skill: Int = 0, var target: Int = -1) {
    var pay: IntArray? = null
    fun clone(): SkillUse { val u = SkillUse(slot, skill, target); u.pay = pay?.copyOf(); return u }
    override fun toString(): String = "f$slot:k$skill→$target" + (pay?.let { "$" + Engine.poolText(it) } ?: "")
}
/** 5 energies of any colours → 1 of [get]. */
class Exchange { var give = IntArray(Colours.N); var get = 0 }
/**
 * A whole turn of one side: the exchanges first, then the skill queue — at most one use per fighter, resolved in the
 * queue's order (the original's Skill Queue screen: the order and which colour pays each «any»).
 */
class Turn {
    val exchanges: MutableList<Exchange> = ArrayList()
    val queue: MutableList<SkillUse> = ArrayList()
    val count: Int get() = queue.size
    fun useOf(slot: Int): SkillUse? { for (u in queue) if (u.slot == slot) return u; return null }
    fun clone(): Turn { val t = Turn(); t.exchanges.addAll(exchanges); for (u in queue) t.queue.add(u.clone()); return t }
    override fun toString(): String = (if (exchanges.size > 0) "x${exchanges.size} " else "") + queue.joinToString(" ") { it.toString() }
}

/**
 * What happened, in order, for the screen and the cases. kind: turn, income, exchange, queue, cast, fizzle, counter,
 * reflect, damage, heal, status, invisible, tick, skip, contact, ignored, trap, alt, passive, energy, expire, out, end.
 */
class MatchEvent(val kind: String, val turn: Int, val side: Int, val slot: Int = -1, val tside: Int = -1, val tslot: Int = -1, val value: Int = 0, val text: String? = null) {
    override fun toString(): String = "T$turn $kind s${side}f$slot" + (if (tside >= 0) "→s${tside}f$tslot" else "") + " v$value " + (text ?: "")
}

/** SplitMix64: the one random source of a match (the energy, Збити з думки's random enemy, energy removal). */
class Rng(var state: ULong) {
    fun next(): ULong {
        state += 0x9E3779B97F4A7C15UL; var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9UL; z = (z xor (z shr 27)) * 0x94D049BB133111EBUL; return z xor (z shr 31)
    }
    /** 0 .. n−1. */
    fun next(n: Int): Int = if (n <= 1) 0 else (next() % n.toULong()).toInt()
    fun clone(): Rng = Rng(state)
}
