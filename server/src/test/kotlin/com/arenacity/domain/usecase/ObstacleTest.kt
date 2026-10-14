package com.arenacity.domain.usecase

import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.model.Obstacle
import com.arenacity.domain.model.ObstacleState
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.util.UUID

/**
 * Obstacles are off (the owner, 14 October 2026, pass 3: «прибери камни и т.д., которые случайно появляются — уберём пока
 * этот функционал»; Obstacles.ENABLED, V40 took every club's away): none turn up, a row left in the table is neither shown
 * nor in the way, and there is nothing to clear. The rules (Obstacles, the clearing use cases) stay for later.
 */
class ObstacleTest {
    private val clock = FixedClock()
    private val clubs = FakeClubRepository()
    private val buildings = FakeBuildingRepository()
    private val obstacles = FakeObstacleRepository()
    private val service = ClubService(clubs, buildings, clock, obstacles = obstacles, lands = TestLand.NOTCHED)
    private val create = CreateClubUseCase(clubs, buildings, clock, emptyList(), lands = TestLand.NOTCHED)
    private val get = GetClubUseCase(service)
    private val place = PlaceBuildingUseCase(service, buildings, clock)
    private val clear = ClearObstacleUseCase(service, obstacles, clock)
    private val user = UUID.randomUUID()

    @Test
    fun `no obstacle turns up, and one left over is neither shown nor in the way`() = runBlocking<Unit> {
        assertFalse(Obstacles.ENABLED)
        val club = create(user, "Red Lions", "#AA0000", "#FFFFFF", "UTC")
        assertEquals(emptyList(), get(user).obstacles)
        clock.advanceSeconds(Obstacles.SPAWN_SECONDS * 30)
        assertEquals(emptyList(), get(user).obstacles)
        assertEquals(emptyList(), obstacles.listByClub(club.id), "nothing spawned behind the scenes")
        // a row from before V40: not shown, a building may stand there, it cannot be cleared
        val old = Obstacle(UUID.randomUUID(), club.id, ObstacleKind.ROCK, 24, 24, ObstacleState.IDLE, null, 3, clock.now(), clock.now())
        obstacles.create(old)
        assertEquals(emptyList(), get(user).obstacles)
        clubs.update(clubs.rows.getValue(club.id).copy(coins = 10_000))
        place(user, BuildingType.DECOR, 24, 24, 0)
        assertFailsWith<NotFoundException> { clear(user, old.id) }
    }
}
