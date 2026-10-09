package com.matchdayclub.domain.usecase.athlete

import com.matchdayclub.common.time.Clock
import com.matchdayclub.common.tx.NoTx
import com.matchdayclub.common.tx.Tx
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

/** When a basketball or tennis court is first completed, the new section gets its starter squad (once). */
class SectionSquadOnCourt(private val athletes: AthleteRepository, private val clock: Clock) : com.matchdayclub.domain.usecase.club.BuildingListener {
    override suspend fun onCompleted(building: Building) {
        val sport = com.matchdayclub.domain.rules.Sections.sportOfVenue(building.type) ?: return
        if (sport == Sport.FOOTBALL || building.level != 1) return
        if (athletes.listByClub(building.clubId).any { it.sport == sport }) return
        athletes.createAll(AthleteFactory.starterSquad(building.clubId, sport, clock.now()))
    }
}

/** Which sport sections a club fields: enough athletes for a lineup and (for humans) an operational venue. */
class SectionService(private val buildings: com.matchdayclub.domain.repository.BuildingRepository, private val athletes: AthleteRepository) {
    suspend fun active(club: Club, squad: List<Athlete>? = null, clubBuildings: List<Building>? = null): List<Sport> {
        val list = squad ?: athletes.listByClub(club.id)
        fun enough(s: Sport) = list.count { it.sport == s } >= com.matchdayclub.domain.rules.Sections.profile(s).startersCount
        if (club.isBot) return com.matchdayclub.domain.rules.Sections.ORDER.filter(::enough)
        val bs = clubBuildings ?: buildings.listByClub(club.id)
        return com.matchdayclub.domain.rules.Sections.ORDER.filter { s ->
            enough(s) && (s == Sport.FOOTBALL || bs.any { it.type == com.matchdayclub.domain.rules.Sections.venue(s) && com.matchdayclub.domain.rules.Sections.venueOperational(it) })
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
        athletes.findById(t.athleteId)?.let { a -> athletes.update(a.withAttribute(t.attribute, (a.attribute(t.attribute) + t.delta).coerceAtMost(99)).copy(updatedAt = now)) }
        return true
    }
}

class ListAthletesUseCase(private val squads: SquadService) {
    suspend operator fun invoke(userId: UUID) = squads.squad(userId)
}

class ClaimAcademyUseCase(private val service: ClubService, private val clubs: ClubRepository, private val athletes: AthleteRepository, private val clock: Clock, private val tx: Tx = NoTx, private val sections: SectionService? = null, private val progress: com.matchdayclub.domain.usecase.economy.ProgressTracker = com.matchdayclub.domain.usecase.economy.ProgressTracker { _, _ -> }) {
    suspend operator fun invoke(userId: UUID, sport: Sport = Sport.FOOTBALL): Athlete = tx {
        val st = service.state(userId, lock = true)
        if (sport != Sport.FOOTBALL && sections != null && sport !in sections.active(st.club, clubBuildings = st.buildings)) throw ValidationException("Build the venue for {0} first", com.matchdayclub.common.i18n.Named("sport." + sport.name))
        val academy = st.buildings.firstOrNull { it.type == BuildingType.ACADEMY && it.state == BuildingState.IDLE } ?: throw ValidationException("Build an academy first")
        val now = clock.now()
        val next = st.club.academyNextAt
        if (next != null && now.isBefore(next)) throw ConflictException("Next academy graduate in {0}", java.time.Duration.between(now, next))
        val q = TrainingRules.academyQuality(academy.level)
        val athlete = AthleteFactory.create(now.toEpochMilli() xor st.club.id.leastSignificantBits, st.club.id, sport, q.first, q.last, null, now)
        athletes.createAll(listOf(athlete))
        clubs.update(st.club.copy(academyNextAt = now.plusSeconds(TrainingRules.ACADEMY_INTERVAL_SECONDS), updatedAt = now))
        progress.record(st.club.id, com.matchdayclub.domain.rules.ProgressEvent.ACADEMY_GRADUATE)
        athlete
    }
}

class StartTrainingUseCase(private val service: ClubService, private val squads: SquadService, private val athletes: AthleteRepository, private val trainings: TrainingRepository, private val clock: Clock, private val progress: com.matchdayclub.domain.usecase.economy.ProgressTracker = com.matchdayclub.domain.usecase.economy.ProgressTracker { _, _ -> }, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, athleteId: UUID, attribute: AttributeKind): Training = tx {
        val st = service.state(userId, lock = true)
        val tc = st.buildings.firstOrNull { it.type == BuildingType.TRAINING_CENTER && it.state == BuildingState.IDLE } ?: throw ValidationException("Build a training centre first")
        val squad = squads.squad(userId)
        if (squad.trainings.size >= TrainingRules.slots(tc.level)) throw ConflictException("All training slots are busy")
        val a = squads.athleteOf(st.club.id, athleteId)
        val now = clock.now()
        if (squad.trainings.any { it.athleteId == a.id }) throw ConflictException("Athlete is already training")
        if (a.isInjured(now)) throw ConflictException("Athlete is injured")
        if (a.currentFatigue(now) > TrainingRules.MAX_FATIGUE_TO_TRAIN) throw ConflictException("Athlete is too tired")
        val venue = st.buildings.firstOrNull { it.type == com.matchdayclub.domain.rules.Sections.venue(a.sport) } ?: throw ValidationException("Build the venue for {0} first", com.matchdayclub.common.i18n.Named("sport." + a.sport.name))
        if (a.attribute(attribute) >= TrainingRules.attributeCap(a.sport, com.matchdayclub.domain.rules.Sections.effectiveLevel(venue))) throw ValidationException("Attribute capped by the venue level; upgrade it")
        athletes.update(a.copy(fatigue = a.currentFatigue(now) + TrainingRules.FATIGUE_PER_TRAINING, fatigueAt = now, updatedAt = now))
        progress.record(st.club.id, com.matchdayclub.domain.rules.ProgressEvent.TRAINING_STARTED)
        trainings.create(Training(UUID.randomUUID(), st.club.id, a.id, attribute, TrainingRules.delta(tc.level), now.plusSeconds(TrainingRules.DURATION_SECONDS), now))
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
        if (remaining > 0) club = service.charge(club, gold = com.matchdayclub.domain.rules.BuildingCatalog.speedUpGold(remaining))
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
        val charged = service.charge(club, gold = com.matchdayclub.domain.rules.BuildingCatalog.speedUpGold(next.epochSecond - now.epochSecond))
        clubs.update(charged.copy(medicalNextAt = null, updatedAt = now))
    }
}
