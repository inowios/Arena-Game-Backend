package com.matchdayclub.domain.rules

import com.matchdayclub.domain.model.*
import com.matchdayclub.engine.model.Position
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

object AthleteFactory {
    private val first = listOf("Alex", "Marco", "Luis", "Diego", "Ivan", "Noah", "Liam", "Mateo", "Kofi", "Ahmed", "Yuki", "Jonas", "Hugo", "Leo", "Max", "Omar", "Sam", "Tomas", "Andriy", "Pavlo", "Taras", "Oleg", "Rafael", "Bruno", "Enzo", "Theo", "Milan", "Nikola", "Erik", "Finn", "Kai", "Jules", "Adam", "Elias", "Mohamed", "Daniel", "Lucas", "Gabriel", "Victor", "Arthur")
    private val last = listOf("Silva", "Rossi", "Garcia", "Muller", "Kovalenko", "Smith", "Costa", "Novak", "Petrov", "Mensah", "Tanaka", "Berg", "Dubois", "Moreau", "Santos", "Fischer", "Jensen", "Bondar", "Shevchenko", "Melnyk", "Haddad", "Okafor", "Lima", "Ferreira", "Weber", "Klein", "Nilsen", "Varga", "Horvat", "Ivanov", "Lopez", "Martin", "Ricci", "Costa", "Moretti", "Kowalski", "Nowak", "Popescu", "Ahmed", "Yilmaz")

    /** Deterministic athlete from a seed. Quality is the centre of attributes; the role attribute gets +10, the opposite −8. */
    fun create(seed: Long, clubId: UUID, sport: Sport, qualityMin: Int, qualityMax: Int, position: Position? = null, now: Instant): Athlete {
        val r = Random(seed)
        val pos = position ?: listOf(Position.GK, Position.DEF, Position.DEF, Position.MID, Position.MID, Position.FWD).random(r)
        val q = r.nextInt(qualityMin, qualityMax + 1)
        fun attr(bias: Int) = (q + bias + r.nextInt(-6, 7)).coerceIn(1, 99)
        val (att, def) = when (pos) { Position.GK -> attr(-8) to attr(10); Position.DEF -> attr(-8) to attr(10); Position.MID -> attr(2) to attr(2); Position.FWD -> attr(10) to attr(-8) }
        val name = "${first.random(r)} ${last.random(r)}"
        val rarity = when { q >= 70 -> Rarity.ELITE; q >= 58 -> Rarity.RARE; else -> Rarity.COMMON }
        return Athlete(UUID(seed, r.nextLong()), clubId, name, sport, pos, att, def, attr(0), attr(0), attr(0), r.nextInt(17, 22), rarity, 0, now, null, 50, now, now)
    }

    /** 14 footballers: 2 GK, 5 DEF, 5 MID, 2 FWD, quality 42..55. */
    fun starterSquad(clubId: UUID, now: Instant): List<Athlete> {
        val base = clubId.mostSignificantBits xor clubId.leastSignificantBits
        val shape = List(2) { Position.GK } + List(5) { Position.DEF } + List(5) { Position.MID } + List(2) { Position.FWD }
        return shape.mapIndexed { i, p -> create(base + i * 7919L, clubId, Sport.FOOTBALL, 42, 55, p, now) }
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
    fun academyQuality(academyLevel: Int): IntRange { val lo = BuildingCatalog.level(BuildingType.ACADEMY, academyLevel).effect; return lo..(lo + 15) }
    fun medicalCooldownSeconds(medicalLevel: Int) = BuildingCatalog.level(BuildingType.MEDICAL_CENTER, medicalLevel).effect2 * 3600L
}
