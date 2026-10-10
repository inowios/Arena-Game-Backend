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
import com.matchdayclub.domain.rules.Careers
import com.matchdayclub.domain.rules.Sections
import com.matchdayclub.domain.rules.Techniques
import com.matchdayclub.domain.usecase.club.ClubService
import com.matchdayclub.domain.usecase.club.ClubState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

data class SeasonCareers(val retired: List<Athlete>, val youth: List<Athlete>)

/** End of a club's season: everyone a year older; veterans may retire, each replaced by a youth prospect. */
class CareerService(private val athletes: AthleteRepository, private val trainings: TrainingRepository, private val clock: Clock) {
    suspend fun seasonEnd(club: Club, seasonNo: Int): SeasonCareers {
        val now = clock.now()
        val squad = athletes.listByClub(club.id)
        val running = trainings.listByClub(club.id).associateBy { it.athleteId }
        val retired = mutableListOf<Athlete>(); val youth = mutableListOf<Athlete>()
        for (a in squad) {
            val r = Random(a.id.mostSignificantBits xor (seasonNo.toLong() * 0x9E3779B97F4A7C15uL.toLong()))
            val older = Careers.age(a, r)
            if (Careers.retires(older, r)) {
                running[a.id]?.let { trainings.delete(it.id) }
                athletes.update(older.copy(retiredAt = now, updatedAt = now)); retired += older
                youth += AthleteFactory.create(r.nextLong(), club.id, a.sport, 38, 50, a.position, now, age = r.nextInt(17, 19))
            } else athletes.update(older.copy(updatedAt = now))
        }
        if (youth.isNotEmpty()) athletes.createAll(youth)
        return SeasonCareers(retired, youth)
    }
}

/** Letting an athlete go (no transfer fee: athletes are never sold). The section must still field a lineup. */
class ReleaseAthleteUseCase(private val service: ClubService, private val athletes: AthleteRepository, private val trainings: TrainingRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, athleteId: UUID) = tx {
        val club = service.lockedClubOf(userId)
        val a = athletes.findById(athleteId)?.takeIf { it.clubId == club.id && it.retiredAt == null } ?: throw NotFoundException("Athlete not found")
        val rest = athletes.listByClub(club.id).filter { it.sport == a.sport && it.id != a.id }
        val profile = Sections.profile(a.sport)
        if (rest.size < profile.startersCount) throw ValidationException("The section needs at least {0} athletes", profile.startersCount)
        for ((pos, range) in profile.requiredRoles) if (a.position == pos && rest.count { it.position == pos } < range.first) throw ValidationException("You'd have nobody left in this position")
        trainings.listByClub(club.id).filter { it.athleteId == a.id }.forEach { trainings.delete(it.id) }
        val now = clock.now()
        athletes.update(a.copy(retiredAt = now, updatedAt = now))
        Unit
    }
}

data class ScoutOffer(val slot: Int, val athlete: Athlete, val kind: String)
data class ScoutReport(val offers: List<ScoutOffer>, val signedToday: Boolean, val nextAt: Instant)

/**
 * Scouting (needs an academy): three players a day — a young prospect, a player in his prime and a veteran — from the
 * club's sections, quality by division and academy level. One may be signed per day, free (athletes are never sold).
 */
class ScoutingUseCases(private val service: ClubService, private val clubs: ClubRepository, private val athletes: AthleteRepository, private val sections: SectionService, private val clock: Clock, private val tx: Tx = NoTx) {
    private fun today(club: Club) = clock.now().atZone(ZoneId.of(club.timezone)).toLocalDate()

    private fun offers(club: Club, day: LocalDate, academyLevel: Int, sports: List<Sport>): List<ScoutOffer> {
        val now = clock.now()
        val base = (club.id.mostSignificantBits xor club.id.leastSignificantBits) + day.toEpochDay() * 0x5DEECE66DL
        val lo = 36 + club.division * 4 + academyLevel * 2
        return listOf("PROSPECT", "PRIME", "VETERAN").mapIndexed { slot, kind ->
            val r = Random(base + slot * 7919L)
            val sport = if (slot == 0 || sports.size == 1) sports.first() else sports[r.nextInt(sports.size)]
            val (q0, q1, age) = when (kind) { "PROSPECT" -> Triple(lo - 6, lo + 2, r.nextInt(17, 20)); "PRIME" -> Triple(lo, lo + 8, r.nextInt(23, 28)); else -> Triple(lo + 4, lo + 12, r.nextInt(30, 33)) }
            ScoutOffer(slot, AthleteFactory.create(r.nextLong(), club.id, sport, q0.coerceIn(30, 85), q1.coerceIn(31, 88), null, now, age = age), kind)
        }
    }

    private suspend fun academyLevel(userId: UUID): Pair<ClubState, Int> {
        val st = service.state(userId)
        val academy = st.buildings.firstOrNull { it.type == BuildingType.ACADEMY && it.state == BuildingState.IDLE } ?: throw ValidationException("Build an academy first")
        return st to academy.level
    }

    suspend fun report(userId: UUID): ScoutReport {
        val (st, level) = academyLevel(userId)
        val day = today(st.club)
        val next = day.plusDays(1).atStartOfDay(ZoneId.of(st.club.timezone)).toInstant()
        return ScoutReport(offers(st.club, day, level, sections.active(st.club, clubBuildings = st.buildings).ifEmpty { listOf(Sport.FOOTBALL) }), st.club.scoutSignedDate == day, next)
    }

    suspend fun sign(userId: UUID, slot: Int): Athlete = tx {
        val (st, level) = academyLevel(userId)
        val club = service.lockedClubOf(userId)
        val day = today(club)
        if (club.scoutSignedDate == day) throw ConflictException("You've already signed a player today — new scouting reports tomorrow")
        val offer = offers(club, day, level, sections.active(club, clubBuildings = st.buildings).ifEmpty { listOf(Sport.FOOTBALL) }).getOrNull(slot) ?: throw NotFoundException("Unknown offer")
        if (athletes.listByClub(club.id).count { it.sport == offer.athlete.sport } >= Careers.squadMax(offer.athlete.sport)) throw ConflictException("The squad is full — release someone first")
        val signed = offer.athlete.copy(id = UUID.randomUUID())
        athletes.createAll(listOf(signed))
        clubs.update(club.copy(scoutSignedDate = day, updatedAt = clock.now()))
        signed
    }
}

/** One step up a technique tree, paid with the athlete's skill points. */
class UpgradeTechniqueUseCase(private val service: ClubService, private val athletes: AthleteRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    data class Result(val athlete: Athlete, val level: Int, val cost: Int)

    suspend operator fun invoke(userId: UUID, athleteId: UUID, key: String): Result = tx {
        val club = service.lockedClubOf(userId)
        val a = athletes.findById(athleteId)?.takeIf { it.clubId == club.id && it.retiredAt == null } ?: throw NotFoundException("Athlete not found")
        val k = key.uppercase()
        if (k !in Techniques.KEYS) throw ValidationException("Unknown technique")
        if (!Techniques.allowed(a.position, k)) throw ValidationException("This athlete cannot learn that move")
        val level = a.technique(k) + 1
        if (level > Techniques.MAX_LEVEL) throw ConflictException("That move is already mastered")
        val cost = Techniques.cost(level, talent = a.talent == k)
        if (a.skillPoints < cost) throw ValidationException("Not enough skill points: {0} needed", cost)
        val now = clock.now()
        val up = athletes.update(a.copy(techniques = a.techniques + (k to level), skillPoints = a.skillPoints - cost, updatedAt = now))
        Result(up, level, cost)
    }
}
