package com.arenacity.it

import com.arenacity.common.tx.DbTx
import com.arenacity.data.repository.*
import com.arenacity.domain.exception.DomainException
import com.arenacity.domain.model.AttributeKind
import com.arenacity.domain.model.AuthProvider
import com.arenacity.domain.model.User
import com.arenacity.domain.rules.MapSpots
import com.arenacity.domain.usecase.athlete.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.testutil.FixedClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Parallel requests must never double-grant or overspend: every club mutation runs in one transaction with the club row locked. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ConcurrencyIT {
    private val clock = FixedClock()
    private val users = UserRepositoryImpl(); private val clubs = ClubRepositoryImpl()
    private val athletes = AthleteRepositoryImpl(); private val trainings = TrainingRepositoryImpl()
    private val spots = ClubSpotRepositoryImpl()
    private val service = ClubService(clubs, clock, spots, ZoneRepositoryImpl())
    private val create = CreateClubUseCase(clubs, clock, listOf(StarterSquadInitializer(athletes, clock)), tx = DbTx)
    private val unlock = UnlockSpotUseCase(service, spots, clock, tx = DbTx)
    private val squads = SquadService(service, athletes, trainings, clock)
    private val train = StartTrainingUseCase(service, squads, athletes, trainings, clock, tx = DbTx)
    private val userId = UUID.randomUUID()

    @BeforeEach
    fun setUp() = runBlocking<Unit> {
        TestDb.fresh()
        users.create(User(userId, AuthProvider.GUEST, "it-device-$userId", null, null, null, true, clock.now(), clock.now()))
        create(userId, "Race FC", "#112233", "#FFFFFF", "UTC"); Unit
    }

    private fun <T> parallel(n: Int, block: suspend (Int) -> T): List<Result<T>> = runBlocking {
        (0 until n).map { i -> async(Dispatchers.IO) { runCatching { block(i) } } }.awaitAll()
    }

    @Test
    fun `parallel unlocks of one spot charge it exactly once`() {
        runBlocking { clubs.update(clubs.findByUserId(userId)!!.copy(coins = 10_000)) }
        val results = parallel(12) { unlock(userId, MapSpots.ACADEMY) }
        assertEquals(1, results.count { it.isSuccess }, "results: $results")
        assertEquals(11, results.count { it.exceptionOrNull() is DomainException })
        assertEquals(7_000L, runBlocking { clubs.findByUserId(userId)!!.coins })
        assertEquals(setOf(MapSpots.ACADEMY), runBlocking { spots.listByClub(clubs.findByUserId(userId)!!.id) })
    }

    @Test
    fun `parallel unlocks never overspend`() {
        runBlocking { clubs.update(clubs.findByUserId(userId)!!.copy(coins = 9_000)) }
        val results = parallel(MapSpots.BUYABLE.size) { i -> unlock(userId, MapSpots.BUYABLE[i].id) }
        val club = runBlocking { clubs.findByUserId(userId)!! }
        val opened = runBlocking { spots.listByClub(club.id) }
        assertEquals(results.count { it.isSuccess }, opened.size, "results: $results")
        assertTrue(club.coins >= 0)
        assertEquals(9_000L - MapSpots.BUYABLE.filter { it.id in opened }.sumOf { it.cost }, club.coins)
    }

    @Test
    fun `a finished training is applied once even when the squad is read in parallel`() {
        val athlete = runBlocking { squads.squad(userId).athletes.minBy { it.mental } }   // stays under the stadium's cap
        runBlocking { train(userId, athlete.id, AttributeKind.MENTAL) }
        clock.advanceSeconds(3600)
        parallel(12) { squads.squad(userId) }
        assertEquals(athlete.mental + 1, runBlocking { athletes.findById(athlete.id)!!.mental })
    }
}
