package com.matchdayclub.domain.model

import com.matchdayclub.engine.model.Attributes
import com.matchdayclub.engine.model.Position
import java.time.Instant
import java.util.UUID
import kotlin.math.max

enum class Sport { FOOTBALL, BASKETBALL, TENNIS }
enum class Rarity { COMMON, RARE, ELITE }
enum class AttributeKind { ATTACK, DEFENSE, PHYSICAL, TECHNIQUE, MENTAL }

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
) {
    val attributes get() = Attributes(attack, defense, physical, technique, mental)
    fun attribute(kind: AttributeKind) = when (kind) { AttributeKind.ATTACK -> attack; AttributeKind.DEFENSE -> defense; AttributeKind.PHYSICAL -> physical; AttributeKind.TECHNIQUE -> technique; AttributeKind.MENTAL -> mental }
    fun withAttribute(kind: AttributeKind, v: Int) = when (kind) {
        AttributeKind.ATTACK -> copy(attack = v); AttributeKind.DEFENSE -> copy(defense = v); AttributeKind.PHYSICAL -> copy(physical = v); AttributeKind.TECHNIQUE -> copy(technique = v); AttributeKind.MENTAL -> copy(mental = v)
    }
    /** Fatigue recovers 4 points per hour passively. */
    fun currentFatigue(now: Instant): Int = max(0, fatigue - ((now.epochSecond - fatigueAt.epochSecond) / 900).toInt())
    fun isInjured(now: Instant) = injuredUntil != null && now.isBefore(injuredUntil)
    val overall: Int get() = toEngine(Instant.EPOCH).overall
    fun toEngine(now: Instant) = com.matchdayclub.engine.model.Athlete(id.toString(), name, position, attributes, currentFatigue(now), isInjured(now), morale)
}

data class Training(val id: UUID, val clubId: UUID, val athleteId: UUID, val attribute: AttributeKind, val delta: Int, val endsAt: Instant, val createdAt: Instant) {
    fun isDone(now: Instant) = !now.isBefore(endsAt)
}
