package com.arenacity.domain.model

import com.arenacity.engine.model.Attributes
import com.arenacity.engine.model.Position
import java.time.Instant
import java.util.UUID
import kotlin.math.max

enum class Sport { FOOTBALL, BASKETBALL, TENNIS }
enum class Rarity { COMMON, RARE, ELITE }
enum class AttributeKind { ATTACK, DEFENSE, PHYSICAL, TECHNIQUE, MENTAL }

const val FATIGUE_RECOVERY_SECONDS = 60L

data class Athlete(
    val id: UUID,
    val clubId: UUID,
    val name: String,
    val sport: Sport,
    val position: Position,
    val attack: Int, val defense: Int, val physical: Int, val technique: Int, val mental: Int,
    val age: Int,
    val rarity: Rarity,
    val fatigue: Int,
    val fatigueAt: Instant,
    val injuredUntil: Instant?,
    val morale: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Ceiling of the attribute average that training can reach (careers). */
    val potential: Int = 60,
    /** Set when the athlete retired or was released; the row stays for old match highlights. */
    val retiredAt: Instant? = null,
    /** Match experience toward the next attribute point (see Growth). */
    val xp: Int = 0,
    /** Levels of the learned techniques by key (see Techniques), skill points to spend, and experience over the career. */
    val techniques: Map<String, Int> = emptyMap(),
    val skillPoints: Int = 0,
    val xpTotal: Int = 0,
    /** The move he was born for: its steps come cheaper (see Techniques.cost). */
    val talent: String? = null,
    /** The card kind he is printed on (plan §3b); a better kind raises the rating and the level ceiling. */
    val cardKind: CardKind = CardKind.CLASSIC,
) {
    fun technique(key: String): Int = techniques[key] ?: 0
    val attributes get() = Attributes(attack, defense, physical, technique, mental)
    val attributeAverage: Int get() = (attack + defense + physical + technique + mental) / 5
    fun attribute(kind: AttributeKind) = when (kind) { AttributeKind.ATTACK -> attack; AttributeKind.DEFENSE -> defense; AttributeKind.PHYSICAL -> physical; AttributeKind.TECHNIQUE -> technique; AttributeKind.MENTAL -> mental }
    fun withAttribute(kind: AttributeKind, v: Int) = when (kind) {
        AttributeKind.ATTACK -> copy(attack = v); AttributeKind.DEFENSE -> copy(defense = v); AttributeKind.PHYSICAL -> copy(physical = v); AttributeKind.TECHNIQUE -> copy(technique = v); AttributeKind.MENTAL -> copy(mental = v)
    }
    /** Fatigue recovers a point a minute passively (a live match costs ~22; three in a row and the squad needs a rest). */
    fun currentFatigue(now: Instant): Int = max(0, fatigue - ((now.epochSecond - fatigueAt.epochSecond) / FATIGUE_RECOVERY_SECONDS).toInt())
    fun isInjured(now: Instant) = injuredUntil != null && now.isBefore(injuredUntil)
    val overall: Int get() = toEngine(Instant.EPOCH).overall
    fun toEngine(now: Instant) = com.arenacity.engine.model.Athlete(id.toString(), name, position, attributes, currentFatigue(now), isInjured(now), morale)
}

/** A session at the training centre: an attribute exercise (+[delta]) or, with [drill], technique work (+[delta] skill points). */
data class Training(val id: UUID, val clubId: UUID, val athleteId: UUID, val attribute: AttributeKind, val delta: Int, val endsAt: Instant, val createdAt: Instant, val drill: Boolean = false) {
    fun isDone(now: Instant) = !now.isBefore(endsAt)
    /** What the client shows: the attribute key, or SKILL for a drill. */
    val kindKey: String get() = if (drill) "SKILL" else attribute.name
}

/** The six card kinds on the six Arena frames: a rating bonus and a level ceiling, opened by the club level. */
enum class CardKind(val bonus: Int, val cap: Int, val unlockLevel: Int) {
    CLASSIC(0, 10, 1), TOURNAMENT(2, 15, 3), PREMIUM(4, 20, 5), GOLD(6, 25, 8), LEGEND(8, 30, 12), ARENA(10, 40, 16);
    companion object {
        fun xpForLevel(level: Int) = 100 * level
        /** The card level the career experience reaches, within the kind's ceiling (mirrors the client's Progression). */
        fun levelFor(xpTotal: Int, kind: CardKind): Int { var xp = xpTotal; var l = 1; while (l < kind.cap && xp >= xpForLevel(l)) { xp -= xpForLevel(l); l++ }; return l }
    }
}
