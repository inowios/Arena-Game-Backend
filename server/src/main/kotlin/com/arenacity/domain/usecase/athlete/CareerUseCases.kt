package com.arenacity.domain.usecase.athlete

import com.arenacity.common.time.Clock
import com.arenacity.domain.model.*
import com.arenacity.domain.repository.AthleteRepository
import com.arenacity.domain.repository.TrainingRepository
import com.arenacity.domain.rules.AthleteFactory
import com.arenacity.domain.rules.Careers
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
