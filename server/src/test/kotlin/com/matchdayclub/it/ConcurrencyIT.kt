package com.matchdayclub.it

import com.matchdayclub.common.tx.DbTx
import com.matchdayclub.data.repository.*
import com.matchdayclub.domain.exception.DomainException
import com.matchdayclub.domain.model.AttributeKind
import com.matchdayclub.domain.model.AuthProvider
import com.matchdayclub.domain.model.User
import com.matchdayclub.domain.rules.BuildingType
import com.matchdayclub.domain.usecase.athlete.*
import com.matchdayclub.domain.usecase.club.*
import com.matchdayclub.testutil.FixedClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** Parallel requests must never double-grant or overspend: every club mutation runs in one transaction with the club row locked. */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class ConcurrencyIT {
    private val clock = FixedClock()
    private val users = UserRepositoryImpl(); private val clubs = ClubRepositoryImpl(); private val buildings = BuildingRepositoryImpl()
    private val athletes = AthleteRepositoryImpl(); private val trainings = TrainingRepositoryImpl()
    private val service = ClubService(clubs, buildings, clock)
    private val create = CreateClubUseCase(clubs, buildings, clock, listOf(StarterSquadInitializer(athletes, clock)), tx = DbTx)
    private val collect = CollectIncomeUseCase(service, clubs, buildings, clock, tx = DbTx)
    private val place = PlaceBuildingUseCase(service, buildings, clock, tx = DbTx)
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
    fun `parallel collects grant the stored income exactly once`() {
        val office = runBlocking { service.state(userId).buildings.first { it.type == BuildingType.TICKET_OFFICE } }
        clock.advanceSeconds(2 * 3600)
        val results = parallel(16) { collect(userId, office.id).coins }
        val granted = results.mapNotNull { it.getOrNull() }
        assertEquals(264L, granted.sum(), "results: $results")   // 2 h × 120 +10 % atmosphere (next to the stadium)
        assertEquals(2_500L + 264, runBlocking { clubs.findByUserId(userId)!!.coins })
    }

    @Test
    fun `parallel purchases never overspend`() {
        runBlocking { clubs.update(clubs.findByUserId(userId)!!.copy(coins = 1_000)) }
        val results = parallel(10) { i -> place(userId, BuildingType.DECOR, i, 11, 0) }   // decor costs 200 and is instant
        assertEquals(5, results.count { it.isSuccess }, "results: $results")
        assertEquals(5, results.count { it.exceptionOrNull() is DomainException })
        assertEquals(0L, runBlocking { clubs.findByUserId(userId)!!.coins })
    }

    @Test
    fun `a finished training is applied once even when the squad is read in parallel`() {
        runBlocking {
            place(userId, BuildingType.TRAINING_CENTER, 0, 10, 0)
            clock.advanceSeconds(3600)
        }
        val athlete = runBlocking { squads.squad(userId).athletes.minBy { it.mental } }   // stays under the pitch cap
        runBlocking { train(userId, athlete.id, AttributeKind.MENTAL) }
        clock.advanceSeconds(3600)
        parallel(12) { squads.squad(userId) }
        assertEquals(athlete.mental + 1, runBlocking { athletes.findById(athlete.id)!!.mental })
    }
}
