package com.matchdayclub.domain.rules

import com.matchdayclub.domain.model.*
import com.matchdayclub.engine.model.Position
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

object AthleteFactory {
    private val first = listOf("Alex", "Marco", "Luis", "Diego", "Ivan", "Noah", "Liam", "Mateo", "Kofi", "Ahmed", "Yuki", "Jonas", "Hugo", "Leo", "Max", "Omar", "Sam", "Tomas", "Andriy", "Pavlo", "Taras", "Oleg", "Rafael", "Bruno", "Enzo", "Theo", "Milan", "Nikola", "Erik", "Finn", "Kai", "Jules", "Adam", "Elias", "Mohamed", "Daniel", "Lucas", "Gabriel", "Victor", "Arthur")
    private val last = listOf("Silva", "Rossi", "Garcia", "Muller", "Kovalenko", "Smith", "Costa", "Novak", "Petrov", "Mensah", "Tanaka", "Berg", "Dubois", "Moreau", "Santos", "Fischer", "Jensen", "Bondar", "Shevchenko", "Melnyk", "Haddad", "Okafor", "Lima", "Ferreira", "Weber", "Klein", "Nilsen", "Varga", "Horvat", "Ivanov", "Lopez", "Martin", "Ricci", "Costa", "Moretti", "Kowalski", "Nowak", "Popescu", "Ahmed", "Yilmaz")

    /** Positions drawn for random athletes of a sport (academy graduates, scouting). */
    private val positionPool = mapOf(
        Sport.FOOTBALL to listOf(Position.GK, Position.DEF, Position.DEF, Position.MID, Position.MID, Position.FWD),
        Sport.BASKETBALL to listOf(Position.G, Position.G, Position.F, Position.F, Position.C),
        Sport.TENNIS to listOf(Position.S),
    )

    /** Attribute bias by role (attack, defense, physical, technique, mental) for non-football roles. */
    private val bias = mapOf(
        Position.G to intArrayOf(4, -4, -4, 8, 4),
        Position.F to intArrayOf(8, 0, 4, 0, -4),
        Position.C to intArrayOf(-4, 10, 10, -8, -2),
        Position.S to intArrayOf(2, 0, 0, 2, 0),
    )

    fun positionsFor(sport: Sport): List<Position> = positionPool.getValue(sport).distinct()

    /** Deterministic athlete from a seed. Quality is the centre of attributes; the role attribute gets +10, the opposite −8. */
    fun create(seed: Long, clubId: UUID, sport: Sport, qualityMin: Int, qualityMax: Int, position: Position? = null, now: Instant, age: Int? = null): Athlete {
        val r = Random(seed)
        val pos = position ?: positionPool.getValue(sport).random(r)
        val q = r.nextInt(qualityMin, qualityMax + 1)
        fun attr(b: Int) = (q + b + r.nextInt(-6, 7)).coerceIn(1, 99)
        val b = bias[pos]
        val (att, def) = when (pos) {
            Position.GK -> attr(-8) to attr(10); Position.DEF -> attr(-8) to attr(10); Position.MID -> attr(2) to attr(2); Position.FWD -> attr(10) to attr(-8)
            else -> attr(b!![0]) to attr(b[1])
        }
        val name = "${first.random(r)} ${last.random(r)}"
        val rarity = when { q >= 70 -> Rarity.ELITE; q >= 58 -> Rarity.RARE; else -> Rarity.COMMON }
        val a = Athlete(UUID(seed, r.nextLong()), clubId, name, sport, pos, att, def, attr(b?.get(2) ?: 0), attr(b?.get(3) ?: 0), attr(b?.get(4) ?: 0), r.nextInt(17, 22), rarity, 0, now, null, 50, now, now)
        // separate stream so potential/age overrides don't change the attributes above (balance, tests)
        val pr = Random(seed xor 0x7A11_5EEDL)
        val finalAge = age ?: a.age
        return a.copy(age = finalAge, potential = Careers.potentialFor(finalAge, a.attributeAverage, pr))
    }

    /** Squad shape per sport: football 14 (2 GK, 5 DEF, 5 MID, 2 FWD), basketball 8 (3 G, 3 F, 2 C), tennis 3. */
    fun shape(sport: Sport): List<Position> = when (sport) {
        Sport.FOOTBALL -> List(2) { Position.GK } + List(5) { Position.DEF } + List(5) { Position.MID } + List(2) { Position.FWD }
        Sport.BASKETBALL -> List(3) { Position.G } + List(3) { Position.F } + List(2) { Position.C }
        Sport.TENNIS -> List(3) { Position.S }
    }

    /** 14 footballers, quality 42..55. */
    fun starterSquad(clubId: UUID, now: Instant): List<Athlete> = squad(clubId, now, 42, 55)

    /** Starter squad of a new section (granted when its court is first completed). */
    fun starterSquad(clubId: UUID, sport: Sport, now: Instant): List<Athlete> = squad(clubId, now, 42, 55, sport)

    fun squad(clubId: UUID, now: Instant, qualityMin: Int, qualityMax: Int, sport: Sport = Sport.FOOTBALL): List<Athlete> {
        val base = (clubId.mostSignificantBits xor clubId.leastSignificantBits) + sport.ordinal * 104_729L
        return shape(sport).mapIndexed { i, p -> create(base + i * 7919L, clubId, sport, qualityMin, qualityMax, p, now) }
    }
}

object TrainingRules {
    const val DURATION_SECONDS = 3600L
    const val FATIGUE_PER_TRAINING = 15
    const val MAX_FATIGUE_TO_TRAIN = 80
    const val ACADEMY_INTERVAL_SECONDS = 8 * 3600L

    fun slots(trainingCenterLevel: Int) = BuildingCatalog.level(BuildingType.TRAINING_CENTER, trainingCenterLevel).effect
    fun delta(trainingCenterLevel: Int) = BuildingCatalog.level(BuildingType.TRAINING_CENTER, trainingCenterLevel).effect2
    fun attributeCap(pitchLevel: Int) = BuildingCatalog.level(BuildingType.PITCH, pitchLevel).effect
    /** Cap for a sport's athletes: the level of that section's venue (stadium, basketball court, tennis court). */
    fun attributeCap(sport: Sport, venueLevel: Int) = BuildingCatalog.level(Sections.venue(sport), venueLevel.coerceIn(1, 3)).effect
    fun academyQuality(academyLevel: Int): IntRange { val lo = BuildingCatalog.level(BuildingType.ACADEMY, academyLevel).effect; return lo..(lo + 15) }
    fun medicalCooldownSeconds(medicalLevel: Int) = BuildingCatalog.level(BuildingType.MEDICAL_CENTER, medicalLevel).effect2 * 3600L
}

/** Sport sections: football always, basketball/tennis once their court is built. */
object Sections {
    val ORDER = listOf(Sport.FOOTBALL, Sport.BASKETBALL, Sport.TENNIS)

    fun venue(sport: Sport): BuildingType = when (sport) { Sport.FOOTBALL -> BuildingType.PITCH; Sport.BASKETBALL -> BuildingType.BASKETBALL_COURT; Sport.TENNIS -> BuildingType.TENNIS_COURT }
    fun sportOfVenue(type: BuildingType): Sport? = ORDER.firstOrNull { venue(it) == type }

    fun profile(sport: Sport): com.matchdayclub.engine.model.SportProfile = when (sport) {
        Sport.FOOTBALL -> com.matchdayclub.engine.model.FOOTBALL
        Sport.BASKETBALL -> com.matchdayclub.engine.model.BASKETBALL
        Sport.TENNIS -> com.matchdayclub.engine.model.TENNIS
    }

    /** A venue counts once its first construction is done; it stays usable while being upgraded to the next level. */
    fun venueOperational(b: Building): Boolean = b.state == BuildingState.IDLE || b.level > 1
    /** The level that applies right now (an upgrade in progress still plays at the previous level). */
    fun effectiveLevel(b: Building): Int = if (b.state == BuildingState.UPGRADING) (b.level - 1).coerceAtLeast(1) else b.level

    /** Sections a bot club plays by division: entry divisions football only, then basketball, then tennis. */
    fun botSections(division: Int): List<Sport> = when {
        division >= 5 -> ORDER
        division >= 3 -> listOf(Sport.FOOTBALL, Sport.BASKETBALL)
        else -> listOf(Sport.FOOTBALL)
    }

    /** Table points for one section result. */
    fun points(forScore: Int, againstScore: Int) = when { forScore > againstScore -> 3; forScore == againstScore -> 1; else -> 0 }
}
