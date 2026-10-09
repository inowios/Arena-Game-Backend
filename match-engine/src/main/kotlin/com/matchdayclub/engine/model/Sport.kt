package com.matchdayclub.engine.model

/** How strongly a role contributes to each team component (per sport). Within a component weights are normalised. */
data class RoleWeights(val attack: Double = 0.0, val defense: Double = 0.0, val midfield: Double = 0.0, val keeper: Double = 0.0)

enum class SimMode { TIMED, SETS }

/**
 * Everything that differs between sports. The simulator itself is sport-agnostic.
 * @param possessions number of possession cycles per match (TIMED)
 * @param baseChanceRate probability a possession produces a scoring chance for equal teams
 * @param baseConversion probability a chance becomes a score for equal teams
 */
data class SportProfile(
    val id: String,
    val startersCount: Int,
    val benchMax: Int,
    val possessions: Int,
    val baseChanceRate: Double,
    val baseConversion: Double,
    val scoreName: String,
    val durationMinutes: Int,
    val homeAdvantage: Double,
    val maxSubs: Int,
    val roles: Map<Position, RoleWeights>,
    /** Rating used for a role with nobody fit in it. */
    val fallbackRating: Map<Position, Double>,
    val shooterWeight: Map<Position, Double>,
    /** Exactly-this-many / at-least constraints for a valid lineup (e.g. football: one GK). */
    val requiredRoles: Map<Position, IntRange> = emptyMap(),
    val mode: SimMode = SimMode.TIMED,
    val periods: Int = 2,
    val scoreValue: Int = 1,
    /** Chance that a chance is a long-range attempt worth [bonusValue] (basketball 3-pointer); conversion × [bonusConversion]. */
    val bonusShotChance: Double = 0.0,
    val bonusValue: Int = 0,
    val bonusConversion: Double = 1.0,
    val allowDraw: Boolean = true,
    val overtimePossessions: Int = 0,
    val injuryRate: Double = 0.0015,
    val fatigueRate: Double = 0.15,
    /** Probability that a shot not scored is "saved" (vs missed) — only cosmetic for events. */
    val saveShare: Double = 0.55,
    // --- SETS mode (tennis)
    val setsToWin: Int = 2,
    val gamesPerSet: Int = 6,
    val serveWinBase: Double = 0.62,
    val serveEdge: Double = 0.75,
    /** Score credited to the club that turns up when the opponent has no section (walkover). */
    val walkover: Score = Score(3, 0),
    /** How strongly rating gaps translate into chances / conversions (lower = more upsets over many possessions). */
    val chanceExp: Double = 1.0,
    val conversionExp: Double = 0.6,
    /** Possessions alternate strictly (basketball) instead of being won by the stronger midfield (football). */
    val alternating: Boolean = false,
) {
    val positions: Set<Position> get() = roles.keys
}

val FOOTBALL = SportProfile(
    id = "football", startersCount = 11, benchMax = 7, possessions = 120, baseChanceRate = 0.20, baseConversion = 0.11,
    scoreName = "goal", durationMinutes = 90, homeAdvantage = 0.09, maxSubs = 5,
    roles = mapOf(
        Position.GK to RoleWeights(defense = 0.2, keeper = 1.0),
        Position.DEF to RoleWeights(defense = 0.6),
        Position.MID to RoleWeights(attack = 0.4, defense = 0.2, midfield = 1.0),
        Position.FWD to RoleWeights(attack = 0.6),
    ),
    fallbackRating = mapOf(Position.GK to 30.0, Position.DEF to 35.0, Position.MID to 35.0, Position.FWD to 35.0),
    shooterWeight = mapOf(Position.FWD to 3.0, Position.MID to 1.0, Position.DEF to 0.2, Position.GK to 0.0),
    requiredRoles = mapOf(Position.GK to 1..1),
    walkover = Score(3, 0),
)

/** 4 quarters of ~25 possessions per team; 2- and 3-point attempts; no draws (5-minute overtimes, then sudden death). */
val BASKETBALL = SportProfile(
    id = "basketball", startersCount = 5, benchMax = 5, possessions = 196, baseChanceRate = 0.86, baseConversion = 0.47,
    scoreName = "point", durationMinutes = 48, homeAdvantage = 0.08, maxSubs = 5,
    roles = mapOf(
        Position.G to RoleWeights(attack = 0.35, defense = 0.25, midfield = 0.7),
        Position.F to RoleWeights(attack = 0.45, defense = 0.35, midfield = 0.3, keeper = 0.3),
        Position.C to RoleWeights(attack = 0.2, defense = 0.4, keeper = 0.7),
    ),
    fallbackRating = mapOf(Position.G to 35.0, Position.F to 35.0, Position.C to 35.0),
    shooterWeight = mapOf(Position.G to 1.2, Position.F to 1.4, Position.C to 1.0),
    periods = 4, scoreValue = 2, bonusShotChance = 0.34, bonusValue = 3, bonusConversion = 0.74,
    allowDraw = false, overtimePossessions = 20, injuryRate = 0.0006, fatigueRate = 0.06, saveShare = 0.3,
    walkover = Score(20, 0), chanceExp = 0.35, conversionExp = 0.3, alternating = true,
)

/** Singles, best of three sets; points on serve decide games. */
val TENNIS = SportProfile(
    id = "tennis", startersCount = 1, benchMax = 1, possessions = 0, baseChanceRate = 0.0, baseConversion = 0.0,
    scoreName = "set", durationMinutes = 0, homeAdvantage = 0.01, maxSubs = 0,
    roles = mapOf(Position.S to RoleWeights(attack = 1.0, defense = 1.0, midfield = 1.0, keeper = 1.0)),
    fallbackRating = mapOf(Position.S to 30.0),
    shooterWeight = mapOf(Position.S to 1.0),
    requiredRoles = mapOf(Position.S to 1..1),
    mode = SimMode.SETS, allowDraw = false, injuryRate = 0.0, fatigueRate = 0.02,
    setsToWin = 2, gamesPerSet = 6, serveWinBase = 0.62, serveEdge = 0.42,
    walkover = Score(2, 0),
)

val SPORTS: Map<String, SportProfile> = listOf(FOOTBALL, BASKETBALL, TENNIS).associateBy { it.id }
