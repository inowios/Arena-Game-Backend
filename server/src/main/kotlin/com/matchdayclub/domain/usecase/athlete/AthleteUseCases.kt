package com.matchdayclub.domain.usecase.athlete

import com.matchdayclub.common.time.Clock
import com.matchdayclub.domain.exception.ConflictException
import com.matchdayclub.domain.exception.NotFoundException
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.repository.AthleteRepository
import com.matchdayclub.domain.repository.ClubRepository
import com.matchdayclub.domain.repository.TrainingRepository
import com.matchdayclub.domain.rules.AthleteFactory
import com.matchdayclub.domain.rules.BuildingType
import com.matchdayclub.domain.rules.TrainingRules
import com.matchdayclub.domain.usecase.club.ClubInitializer
import com.matchdayclub.domain.usecase.club.ClubService
import java.util.UUID

class StarterSquadInitializer(private val athletes: AthleteRepository, private val clock: Clock) : ClubInitializer {
    override suspend fun onClubCreated(club: Club) = athletes.createAll(AthleteFactory.starterSquad(club.id, clock.now()))
}

data class Squad(val athletes: List<Athlete>, val trainings: List<Training>, val slots: Int)

/** Loads the squad and applies finished trainings ("finalise on read"). */
class SquadService(private val service: ClubService, private val athletes: AthleteRepository, private val trainings: TrainingRepository, private val clock: Clock) {
    suspend fun squad(userId: UUID): Squad {
        val st = service.state(userId)
        val now = clock.now()
        val active = trainings.listByClub(st.club.id).filter { t ->
            if (!t.isDone(now)) true else {
                athletes.findById(t.athleteId)?.let { a -> athletes.update(a.withAttribute(t.attribute, (a.attribute(t.attribute) + t.delta).coerceAtMost(99)).copy(updatedAt = now)) }
                trainings.delete(t.id); false
            }
        }
        val tc = st.buildings.firstOrNull { it.type == BuildingType.TRAINING_CENTER && it.state == BuildingState.IDLE }
        return Squad(athletes.listByClub(st.club.id), active, tc?.let { TrainingRules.slots(it.level) } ?: 0)
    }

    suspend fun athleteOf(clubId: UUID, id: UUID): Athlete = athletes.findById(id)?.takeIf { it.clubId == clubId } ?: throw NotFoundException("Athlete not found")
}

class ListAthletesUseCase(private val squads: SquadService) {
    suspend operator fun invoke(userId: UUID) = squads.squad(userId)
}

class ClaimAcademyUseCase(private val service: ClubService, private val clubs: ClubRepository, private val athletes: AthleteRepository, private val clock: Clock) {
    suspend operator fun invoke(userId: UUID): Athlete {
        val st = service.state(userId)
        val academy = st.buildings.firstOrNull { it.type == BuildingType.ACADEMY && it.state == BuildingState.IDLE } ?: throw ValidationException("Build an academy first")
        val now = clock.now()
        val next = st.club.academyNextAt
        if (next != null && now.isBefore(next)) throw ConflictException("Next graduate at $next")
        val q = TrainingRules.academyQuality(academy.level)
        val athlete = AthleteFactory.create(now.toEpochMilli() xor st.club.id.leastSignificantBits, st.club.id, Sport.FOOTBALL, q.first, q.last, null, now)
        athletes.createAll(listOf(athlete))
        clubs.update(st.club.copy(academyNextAt = now.plusSeconds(TrainingRules.ACADEMY_INTERVAL_SECONDS), updatedAt = now))
        return athlete
    }
}

class StartTrainingUseCase(private val service: ClubService, private val squads: SquadService, private val athletes: AthleteRepository, private val trainings: TrainingRepository, private val clock: Clock, private val progress: com.matchdayclub.domain.usecase.economy.ProgressTracker = com.matchdayclub.domain.usecase.economy.ProgressTracker { _, _ -> }) {
    suspend operator fun invoke(userId: UUID, athleteId: UUID, attribute: AttributeKind): Training {
        val st = service.state(userId)
        val tc = st.buildings.firstOrNull { it.type == BuildingType.TRAINING_CENTER && it.state == BuildingState.IDLE } ?: throw ValidationException("Build a training centre first")
        val squad = squads.squad(userId)
        if (squad.trainings.size >= TrainingRules.slots(tc.level)) throw ConflictException("All training slots are busy")
        val a = squads.athleteOf(st.club.id, athleteId)
        val now = clock.now()
        if (squad.trainings.any { it.athleteId == a.id }) throw ConflictException("Athlete is already training")
        if (a.isInjured(now)) throw ConflictException("Athlete is injured")
        if (a.currentFatigue(now) > TrainingRules.MAX_FATIGUE_TO_TRAIN) throw ConflictException("Athlete is too tired")
        val pitch = st.buildings.first { it.type == BuildingType.PITCH }
        if (a.attribute(attribute) >= TrainingRules.attributeCap(pitch.level)) throw ValidationException("Attribute capped by pitch level; upgrade the pitch")
        athletes.update(a.copy(fatigue = a.currentFatigue(now) + TrainingRules.FATIGUE_PER_TRAINING, fatigueAt = now, updatedAt = now))
        progress.record(st.club.id, com.matchdayclub.domain.rules.ProgressEvent.TRAINING_STARTED)
        return trainings.create(Training(UUID.randomUUID(), st.club.id, a.id, attribute, TrainingRules.delta(tc.level), now.plusSeconds(TrainingRules.DURATION_SECONDS), now))
    }
}

class CompleteTrainingUseCase(private val service: ClubService, private val squads: SquadService, private val trainings: TrainingRepository, private val clock: Clock) {
    suspend operator fun invoke(userId: UUID, trainingId: UUID): Athlete {
        val club = service.clubOf(userId)
        val t = trainings.findById(trainingId)?.takeIf { it.clubId == club.id } ?: throw NotFoundException("Training not found")
        if (!t.isDone(clock.now())) throw ConflictException("Training still in progress")
        squads.squad(userId)   // finalises
        return squads.athleteOf(club.id, t.athleteId)
    }
}

class HealAthleteUseCase(private val service: ClubService, private val squads: SquadService, private val clubs: ClubRepository, private val athletes: AthleteRepository, private val clock: Clock) {
    suspend operator fun invoke(userId: UUID, athleteId: UUID): Athlete {
        val st = service.state(userId)
        val med = st.buildings.firstOrNull { it.type == BuildingType.MEDICAL_CENTER && it.state == BuildingState.IDLE } ?: throw ValidationException("Build a medical centre first")
        val now = clock.now()
        val next = st.club.medicalNextAt
        if (next != null && now.isBefore(next)) throw ConflictException("Medical centre busy until $next")
        val a = squads.athleteOf(st.club.id, athleteId)
        if (!a.isInjured(now) && a.currentFatigue(now) == 0) throw ValidationException("Athlete is already fit")
        clubs.update(st.club.copy(medicalNextAt = now.plusSeconds(TrainingRules.medicalCooldownSeconds(med.level)), updatedAt = now))
        return athletes.update(a.copy(injuredUntil = null, fatigue = 0, fatigueAt = now, updatedAt = now))
    }
}
