package com.arenacity.domain.usecase.athlete

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.*
import com.arenacity.domain.repository.AthleteRepository
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.repository.TrainingRepository
import com.arenacity.domain.rules.AthleteFactory
import com.arenacity.domain.rules.BuildingType
import com.arenacity.domain.rules.TrainingRules
import com.arenacity.domain.usecase.club.ClubInitializer
import com.arenacity.domain.usecase.club.ClubService
import java.util.UUID

class StarterSquadInitializer(private val athletes: AthleteRepository, private val clock: Clock) : ClubInitializer {
    override suspend fun onClubCreated(club: Club) = athletes.createAll(AthleteFactory.starterSquad(club.id, clock.now()))
}

/**
 * Which sport sections a club fields: enough athletes for a lineup and (for humans) an operational venue. Football only
 * since 15 October 2026 (rules.Sections; the basketball and tennis arenas that opened the other two, with a starter
 * squad when the first was finished, went).
 */
class SectionService(private val buildings: com.arenacity.domain.repository.BuildingRepository, private val athletes: AthleteRepository) {
    suspend fun active(club: Club, squad: List<Athlete>? = null, clubBuildings: List<Building>? = null): List<Sport> {
        val list = squad ?: athletes.listByClub(club.id)
        fun enough(s: Sport) = list.count { it.sport == s } >= com.arenacity.domain.rules.Sections.profile(s).startersCount
        if (club.isBot) return com.arenacity.domain.rules.Sections.ORDER.filter(::enough)
        val bs = clubBuildings ?: buildings.listByClub(club.id)
        return com.arenacity.domain.rules.Sections.ORDER.filter { s ->
            enough(s) && (s == Sport.FOOTBALL || bs.any { it.type == com.arenacity.domain.rules.Sections.venue(s) && com.arenacity.domain.rules.Sections.venueOperational(it) })
        }
    }
}

data class Squad(val athletes: List<Athlete>, val trainings: List<Training>, val slots: Int)

/** Loads the squad and applies finished trainings ("finalise on read"). */
class SquadService(private val service: ClubService, private val athletes: AthleteRepository, private val trainings: TrainingRepository, private val clock: Clock) {
    suspend fun squad(userId: UUID): Squad {
        val st = service.state(userId)
        val now = clock.now()
        val active = trainings.listByClub(st.club.id).filter { t -> if (!t.isDone(now)) true else { finish(t, now); false } }
        val tc = st.buildings.firstOrNull { it.type == BuildingType.TRAINING_CENTER && it.state == BuildingState.IDLE }
        return Squad(athletes.listByClub(st.club.id), active, tc?.let { TrainingRules.slots(it.level) } ?: 0)
    }

    suspend fun athleteOf(clubId: UUID, id: UUID): Athlete = athletes.findById(id)?.takeIf { it.clubId == clubId } ?: throw NotFoundException("Athlete not found")

    /** Applies a training's gain. Deletes first: only the caller that actually removed the row applies it (no double gain). */
    suspend fun finish(t: Training, now: java.time.Instant): Boolean {
        if (!trainings.delete(t.id)) return false
        athletes.findById(t.athleteId)?.let { a ->
            val grown = if (t.drill) a.copy(skillPoints = a.skillPoints + t.delta) else a.withAttribute(t.attribute, (a.attribute(t.attribute) + t.delta).coerceAtMost(99))
            athletes.update(grown.copy(updatedAt = now))
        }
        return true
    }
}

class ListAthletesUseCase(private val squads: SquadService) {
    suspend operator fun invoke(userId: UUID) = squads.squad(userId)
}

class ClaimAcademyUseCase(private val service: ClubService, private val clubs: ClubRepository, private val athletes: AthleteRepository, private val clock: Clock, private val tx: Tx = NoTx, private val sections: SectionService? = null, private val progress: com.arenacity.domain.usecase.economy.ProgressTracker = com.arenacity.domain.usecase.economy.ProgressTracker { _, _ -> }) {
    suspend operator fun invoke(userId: UUID, sport: Sport = Sport.FOOTBALL): Athlete = tx {
        val st = service.state(userId, lock = true)
        if (sport != Sport.FOOTBALL && sections != null && sport !in sections.active(st.club, clubBuildings = st.buildings)) throw ValidationException("Build the venue for {0} first", com.arenacity.common.i18n.Named("sport." + sport.name))
        val academy = st.buildings.firstOrNull { it.type == BuildingType.ACADEMY && it.state == BuildingState.IDLE } ?: throw ValidationException("Build an academy first")
        val now = clock.now()
        val next = st.club.academyNextAt
        if (next != null && now.isBefore(next)) throw ConflictException("Next academy graduate in {0}", java.time.Duration.between(now, next))
        if (athletes.listByClub(st.club.id).count { it.sport == sport } >= com.arenacity.domain.rules.Careers.squadMax(sport)) throw ConflictException("The squad is full — release someone first")
        val q = TrainingRules.academyQuality(academy.level)
        val athlete = AthleteFactory.create(now.toEpochMilli() xor st.club.id.leastSignificantBits, st.club.id, sport, q.first, q.last, null, now)
        athletes.createAll(listOf(athlete))
        clubs.update(st.club.copy(academyNextAt = now.plusSeconds(TrainingRules.ACADEMY_INTERVAL_SECONDS), updatedAt = now))
        progress.record(st.club.id, com.arenacity.domain.rules.ProgressEvent.ACADEMY_GRADUATE)
        athlete
    }
}

class StartTrainingUseCase(private val service: ClubService, private val squads: SquadService, private val athletes: AthleteRepository, private val trainings: TrainingRepository, private val clock: Clock, private val progress: com.arenacity.domain.usecase.economy.ProgressTracker = com.arenacity.domain.usecase.economy.ProgressTracker { _, _ -> }, private val tx: Tx = NoTx) {
    /** [attribute] null = a technique drill: a skill point for the trees instead of an attribute point. */
    suspend operator fun invoke(userId: UUID, athleteId: UUID, attribute: AttributeKind?): Training = tx {
        val st = service.state(userId, lock = true)
        val tc = st.buildings.firstOrNull { it.type == BuildingType.TRAINING_CENTER && it.state == BuildingState.IDLE } ?: throw ValidationException("Build a training centre first")
        val squad = squads.squad(userId)
        if (squad.trainings.size >= TrainingRules.slots(tc.level)) throw ConflictException("All training slots are busy")
        val a = squads.athleteOf(st.club.id, athleteId)
        val now = clock.now()
        if (squad.trainings.any { it.athleteId == a.id }) throw ConflictException("Athlete is already training")
        if (a.isInjured(now)) throw ConflictException("Athlete is injured")
        if (a.currentFatigue(now) > TrainingRules.MAX_FATIGUE_TO_TRAIN) throw ConflictException("Athlete is too tired")
        val drill = attribute == null
        if (drill && !com.arenacity.domain.rules.Techniques.learns(a.position)) throw ValidationException("This athlete cannot learn that move")
        if (!drill)
        {
            val venue = st.buildings.firstOrNull { it.type == com.arenacity.domain.rules.Sections.venue(a.sport) }
            if (venue == null || a.sport != Sport.FOOTBALL) throw ValidationException("Build the venue for {0} first", com.arenacity.common.i18n.Named("sport." + a.sport.name))
            val venueLevel = com.arenacity.domain.rules.Sections.effectiveLevel(venue)
            if (a.attribute(attribute!!) >= TrainingRules.attributeCap(a.sport, venueLevel)) throw ValidationException("Attribute capped by the venue level; upgrade it")
            if (!com.arenacity.domain.rules.Careers.canTrain(a, TrainingRules.delta(tc.level))) throw ValidationException("This athlete has reached their potential")
        }
        athletes.update(a.copy(fatigue = a.currentFatigue(now) + TrainingRules.FATIGUE_PER_TRAINING, fatigueAt = now, updatedAt = now))
        progress.record(st.club.id, com.arenacity.domain.rules.ProgressEvent.TRAINING_STARTED)
        if (drill) trainings.create(Training(UUID.randomUUID(), st.club.id, a.id, AttributeKind.TECHNIQUE, TrainingRules.DRILL_POINTS, now.plusSeconds(TrainingRules.DRILL_SECONDS), now, drill = true))
        else trainings.create(Training(UUID.randomUUID(), st.club.id, a.id, attribute!!, TrainingRules.delta(tc.level), now.plusSeconds(TrainingRules.DURATION_SECONDS), now))
    }
}

class CompleteTrainingUseCase(private val service: ClubService, private val squads: SquadService, private val trainings: TrainingRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, trainingId: UUID): Athlete = tx {
        val club = service.lockedClubOf(userId)
        val t = trainings.findById(trainingId)?.takeIf { it.clubId == club.id } ?: throw NotFoundException("Training not found")
        if (!t.isDone(clock.now())) throw ConflictException("Training still in progress")
        squads.squad(userId)   // finalises
        squads.athleteOf(club.id, t.athleteId)
    }
}

class HealAthleteUseCase(private val service: ClubService, private val squads: SquadService, private val clubs: ClubRepository, private val athletes: AthleteRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, athleteId: UUID): Athlete = tx {
        val st = service.state(userId, lock = true)
        val med = st.buildings.firstOrNull { it.type == BuildingType.MEDICAL_CENTER && it.state == BuildingState.IDLE } ?: throw ValidationException("Build a medical centre first")
        val now = clock.now()
        val next = st.club.medicalNextAt
        if (next != null && now.isBefore(next)) throw ConflictException("The medical centre is busy for {0}", java.time.Duration.between(now, next))
        val a = squads.athleteOf(st.club.id, athleteId)
        if (!a.isInjured(now) && a.currentFatigue(now) == 0) throw ValidationException("Athlete is already fit")
        clubs.update(st.club.copy(medicalNextAt = now.plusSeconds(TrainingRules.medicalCooldownSeconds(med.level)), updatedAt = now))
        athletes.update(a.copy(injuredUntil = null, fatigue = 0, fatigueAt = now, updatedAt = now))
    }
}

/** Finishes a running training now for gold (spec §6: speed-ups of training timers). */
class SpeedUpTrainingUseCase(private val service: ClubService, private val squads: SquadService, private val trainings: TrainingRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, trainingId: UUID): Pair<Athlete, Club> = tx {
        var club = service.lockedClubOf(userId)
        val t = trainings.findById(trainingId)?.takeIf { it.clubId == club.id } ?: throw NotFoundException("Training not found")
        val now = clock.now()
        val remaining = t.endsAt.epochSecond - now.epochSecond
        if (remaining > 0) club = service.charge(club, gold = com.arenacity.domain.rules.BuildingCatalog.speedUpGold(remaining))
        squads.finish(t, now)
        squads.athleteOf(club.id, t.athleteId) to club
    }
}

/** Ends the medical centre's cooldown for gold, so the next athlete can be treated now (spec §6: healing speed-up). */
class SpeedUpMedicalUseCase(private val service: ClubService, private val clubs: ClubRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID): Club = tx {
        val club = service.lockedClubOf(userId)
        val now = clock.now()
        val next = club.medicalNextAt
        if (next == null || !now.isBefore(next)) throw ConflictException("The medical centre is ready")
        val charged = service.charge(club, gold = com.arenacity.domain.rules.BuildingCatalog.speedUpGold(next.epochSecond - now.epochSecond))
        clubs.update(charged.copy(medicalNextAt = null, updatedAt = now))
    }
}
