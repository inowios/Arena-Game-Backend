package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.Building
import com.arenacity.domain.model.BuildingState
import com.arenacity.domain.model.Obstacle
import com.arenacity.domain.model.ObstacleState
import com.arenacity.domain.repository.RehomeMove
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.domain.usecase.social.GetGroundsUseCase
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.*

/**
 * The land as a mask (the owner's picture, 13 October): the buildable cells follow the painted meadow, every placement is
 * checked cell by cell against it, and a town built on the old square moves onto it once. Here the clubs are built on
 * the old square ([Lands.SQUARE]) and the land then becomes [TestLand.NOTCHED] (its top-right corner is sea, a locked
 * androids' island beside it).
 */
class LandTest {
    private val clock = FixedClock()
    private val clubs = FakeClubRepository()
    private val buildings = FakeBuildingRepository()
    private val obstacles = FakeObstacleRepository()
    private val log = FakeRehomeLog()
    private fun service(lands: LandMap) = ClubService(clubs, buildings, clock, obstacles = obstacles, lands = lands, moves = log)
    private val notched = service(TestLand.NOTCHED)
    private val create = CreateClubUseCase(clubs, buildings, clock, emptyList(), lands = Lands.SQUARE)
    private val get = GetClubUseCase(notched)
    private val place = PlaceBuildingUseCase(notched, buildings, clock)
    private val move = MoveBuildingUseCase(notched, buildings, clock)
    private val draw = PlaceFencesUseCase(notched, buildings, clock)
    private val applyLayout = ApplyLayoutUseCase(notched, buildings, clock)
    private val user = UUID.randomUUID()

    /** A club built on the old square, rich and with no obstacles turning up on their own. */
    private fun newClub(u: UUID = user, name: String = "Red Lions") = runBlocking {
        val c = create(u, name, "#AA0000", "#FFFFFF", "Europe/Kyiv")
        clubs.update(clubs.rows.getValue(c.id).copy(coins = 1_000_000, gold = 100, trophies = 100_000))
        obstacles.clocks[c.id] = clock.now()
        clubs.rows.getValue(c.id)
    }
    private fun at(clubId: UUID, type: BuildingType, x: Int, y: Int, rotation: Int = 0, level: Int = 1) = runBlocking {
        buildings.create(Building(UUID.randomUUID(), clubId, type, level, x, y, rotation, BuildingState.IDLE, null, clock.now(), clock.now(), clock.now()))
    }
    private fun fp(b: Building) = footprint(b.type, b.gridX, b.gridY, b.rotation)

    @Test
    fun `the mask's id is the sha1 of one canonical text, the same in the art pipeline, the client and here`() {
        val main = LandRegion.of("main", null, 0, 0, listOf("##", "#."))
        val isle = LandRegion.of("isle", "TENNIS", 5, 0, listOf("#"))
        // regions sorted by id, `id|lock|x0|y0|w|h|rows joined by /`, a missing lock is "-"; the vector checked in Python too
        assertEquals("isle|TENNIS|5|0|1|1|#\nmain|-|0|0|2|2|##/#.", LandMap.canonical(listOf(main, isle)))
        assertEquals("1e44def402bb7ea46824db0f63392afed4b4ace7", LandMap.sha1(listOf(main, isle)))
        // the organic fixture's id was computed by its Python generator
        assertEquals(TestLand.ORGANIC.id, TestLand.ORGANIC.computedId)
        assertEquals(2304, Lands.SQUARE.main.area); assertEquals(10 to 10, Lands.SQUARE.stadium); assertEquals(4 to 22, Lands.SQUARE.ticket)
        // the served text parses back to the same mask
        for (m in listOf(Lands.SQUARE, TestLand.NOTCHED, TestLand.ORGANIC)) {
            val back = LandMap.parse(m.json)
            assertEquals(m.id, back.id); assertEquals(m.regions.map { it.rows }, back.regions.map { it.rows }); assertEquals(m.bridges, back.bridges)
        }
    }

    @Test
    fun `a broken mask is refused when it loads`() {
        fun file(rows: String, stadium: String = "[10, 10]", more: String = "") =
            """{"version": 1, "id": "x", "cell": 10, "anchors": {"stadium": $stadium, "ticket": [2, 24]}, "regions": [{"id": "main", "lock": null, "x0": 0, "y0": 0, "w": 30, "h": 30, "rows": $rows}$more]}"""
        val full = (0 until 30).joinToString(",", "[", "]") { "\"${"#".repeat(30)}\"" }
        LandMap.parse(file(full))
        assertFailsWith<IllegalArgumentException> { LandMap.parse(file((0 until 30).joinToString(",", "[", "]") { "\"${"#".repeat(if (it == 3) 29 else 30)}\"" })) }   // a short row
        assertFailsWith<IllegalArgumentException> { LandMap.parse(file(full, stadium = "[25, 25]")) }                                                             // the stadium in the sea
        assertFailsWith<IllegalArgumentException> { LandMap.parse(file(full, more = """, {"id": "isle", "lock": "TENNIS", "x0": 29, "y0": 0, "w": 1, "h": 1, "rows": ["#"]}""")) }   // overlapping regions
    }

    @Test
    fun `a new club starts on the mask's anchors, already on that mask`() = runBlocking<Unit> {
        val c = CreateClubUseCase(clubs, buildings, clock, emptyList(), lands = TestLand.ORGANIC)(user, "Green Hills", "#00AA00", "#FFFFFF", "UTC")
        assertEquals(TestLand.ORGANIC.id, c.landMask)
        val list = buildings.listByClub(c.id)
        assertEquals(TestLand.ORGANIC.stadium, list.single { it.type == BuildingType.PITCH }.let { it.gridX to it.gridY })
        assertEquals(TestLand.ORGANIC.ticket, list.single { it.type == BuildingType.TICKET_OFFICE }.let { it.gridX to it.gridY })
        service(TestLand.ORGANIC).state(user)
        assertTrue(log.rows.isEmpty(), "nothing to move")
    }

    @Test
    fun `placing, moving, roads and fences are checked cell by cell against the mask`() = runBlocking<Unit> {
        val club = newClub()
        buildings.setStadium(club.id, 4)   // roads open at stadium 4
        val st = get(user)
        assertEquals(listOf("main"), st.land.ids)
        assertEquals(listOf("android"), notched.lands.locked(st.land).map { it.id })
        // the academy (8×6) would reach into the sea of the cut corner (x >= 30, y <= 0); a tile left it stands on the meadow
        assertEquals("Outside your land", assertFailsWith<ValidationException> { place(user, BuildingType.ACADEMY, 24, -4, 0) }.template)
        // on the build grid's tiles only (14 October, pass 3): a top-left cell even both ways
        assertEquals("Off the build grid", assertFailsWith<ValidationException> { place(user, BuildingType.ACADEMY, 21, -4, 0) }.template)
        val academy = place(user, BuildingType.ACADEMY, 22, -4, 0)
        assertEquals("Outside your land", assertFailsWith<ValidationException> { move(user, academy.id, 24, -4, 0) }.template)
        assertEquals("Off the build grid", assertFailsWith<ValidationException> { move(user, academy.id, 20, -3, 0) }.template)
        move(user, academy.id, 20, -4, 0)
        // roads and fences tile by tile
        assertEquals("Outside your land", assertFailsWith<ValidationException> { draw(user, listOf(28 to 2, 30 to 0), BuildingType.ROAD) }.template)
        assertEquals(1, draw(user, listOf(28 to 2), BuildingType.ROAD).size)
        // the androids' island is land, but locked until its bridge opens it
        assertEquals("The island opens with its bridge", assertFailsWith<ValidationException> { place(user, BuildingType.DECOR, 52, 2, 0) }.template)
        assertEquals("The island opens with its bridge", assertFailsWith<ValidationException> { draw(user, listOf(54 to 4), BuildingType.ROAD) }.template)
        assertEquals("Outside your land", assertFailsWith<ValidationException> { place(user, BuildingType.DECOR, 44, 2, 0) }.template)   // the strait
        // edit mode checks the moved buildings the same way
        assertEquals("Outside your land", assertFailsWith<ValidationException> { applyLayout(user, listOf(Placement(academy.id, 30, -8, 0))) }.template)
    }

    @Test
    fun `a people's island opens with its bridge, as GET club zones has it`() = runBlocking<Unit> {
        val club = newClub()
        val zones = object : com.arenacity.domain.repository.ZoneRepository {
            val rows = mutableListOf<com.arenacity.domain.model.ClubZone>()
            override suspend fun listByClub(clubId: UUID) = rows.filter { it.clubId == clubId }
            override suspend fun upsert(zone: com.arenacity.domain.model.ClubZone) = zone.also { z -> rows.removeAll { it.clubId == z.clubId && it.people == z.people }; rows += z }
        }
        val opened = service(TestLand.NOTCHED).apply { openZones = { id -> openPeoples(zones, id, clock.now()) } }
        val placeThere = PlaceBuildingUseCase(opened, buildings, clock)
        // the bridge still being built: the island stays locked
        zones.upsert(com.arenacity.domain.model.ClubZone(club.id, com.arenacity.domain.model.People.ANDROID, com.arenacity.domain.model.ZoneState.RESTORING, clock.now(), clock.now().plusSeconds(60)))
        assertEquals(listOf("main"), opened.state(user).land.ids)
        assertEquals("The island opens with its bridge", assertFailsWith<ValidationException> { placeThere(user, BuildingType.DECOR, 52, 2, 0) }.template)
        // restored (the zone OPEN once its time is up): the island is the club's land, no longer locked, and visitors see it
        clock.advanceSeconds(61)
        val st = opened.state(user)
        assertEquals(listOf("main", "android"), st.land.ids)
        assertTrue(TestLand.NOTCHED.locked(st.land).isEmpty())
        assertEquals(52, placeThere(user, BuildingType.DECOR, 52, 2, 0).gridX)
        val grounds = GetGroundsUseCase(clubs, buildings, com.arenacity.domain.usecase.athlete.SectionService(buildings, FakeAthleteRepository()), TestLand.NOTCHED)
            .apply { openZones = { id -> openPeoples(zones, id, clock.now()) } }(club.id)
        assertEquals(listOf("main", "android"), grounds.land.ids)
        assertTrue(grounds.buildings.any { it.gridX == 52 && it.gridY == 2 })
    }

    @Test
    fun `an older club moves onto the new mask once, what stood in the sea to the nearest free spot, biggest first`() = runBlocking<Unit> {
        val club = newClub()
        // built on the old square: in the corner that is sea now an academy, a road, a fence and a tree; a sponsor office
        // (4×4) straddling the new shore whose top-left cell (28, -6) is on the meadow, and beside it a ticket office in the
        // sea that moves to the nearest free spot — everything lost is parked first, so none ever lands on the top-left cell
        // of another still waiting (the (club, x, y) uniqueness); and a house on the meadow but off the build grid's tiles
        // (14 October, pass 3: every building on a tile), which moves onto the nearest one
        val academy = at(club.id, BuildingType.ACADEMY, 34, -4, level = 3)
        val court = at(club.id, BuildingType.SPONSOR_OFFICE, 28, -6)
        val office = at(club.id, BuildingType.TICKET_OFFICE, 32, -6, rotation = 90)
        val road = at(club.id, BuildingType.ROAD, 30, -8)
        val fence = at(club.id, BuildingType.FENCE, 32, -8, level = 3)
        val tree = at(club.id, BuildingType.DECOR, 36, -6)
        val stays = at(club.id, BuildingType.FAN_ZONE, 20, 26)
        val house = at(club.id, BuildingType.HOUSE, 1, 31)
        // obstacles out there go: the gold of a cleared one is the club's, an untouched one simply goes
        val ready = Obstacle(UUID.randomUUID(), club.id, ObstacleKind.ROCK, 34, -4, ObstacleState.READY, null, 7, clock.now(), clock.now())
        val idle = Obstacle(UUID.randomUUID(), club.id, ObstacleKind.BUSH, 36, -8, ObstacleState.IDLE, null, 5, clock.now(), clock.now())
        val kept = Obstacle(UUID.randomUUID(), club.id, ObstacleKind.BUSH, 0, 36, ObstacleState.IDLE, null, 5, clock.now(), clock.now())
        listOf(ready, idle, kept).forEach { obstacles.create(it) }
        val gold = clubs.rows.getValue(club.id).gold
        val coins = clubs.rows.getValue(club.id).coins

        val st = get(user)
        assertEquals(TestLand.NOTCHED.id, clubs.rows.getValue(club.id).landMask)
        val list = st.buildings.associateBy { it.id }
        assertTrue(list.values.all { st.land.fits(fp(it)) }, "everything stands on the meadow: ${list.values.filter { !st.land.fits(fp(it)) }}")
        assertTrue(list.values.all { Grid.aligned(it.gridX, it.gridY) }, "everything on a tile: ${list.values.filter { !Grid.aligned(it.gridX, it.gridY) }}")
        for (i in list.values.indices) for (j in i + 1 until list.size) assertFalse(fp(list.values.elementAt(i)).overlaps(fp(list.values.elementAt(j))))
        assertEquals(90, list.getValue(office.id).rotation)
        assertNotEquals(28 to -6, list.getValue(court.id).let { it.gridX to it.gridY })
        assertEquals(3, list.getValue(academy.id).level)
        assertEquals(30 to 2, list.getValue(academy.id).let { it.gridX to it.gridY }, "the academy just below the cut corner, the nearest free spot")
        assertEquals(0 to 30, list.getValue(house.id).let { it.gridX to it.gridY }, "the house onto the nearest tile")
        assertTrue(tree.id in list, "decor moves too")
        // the road and fence tiles in the sea go, refunded at what their levels cost (not left as stray tiles at the shore)
        assertTrue(road.id !in list && fence.id !in list)
        assertEquals(coins + 40 + (30 + 150 + 600), clubs.rows.getValue(club.id).coins)
        assertEquals(20 to 26, list.getValue(stays.id).let { it.gridX to it.gridY })
        // the obstacles
        assertEquals(listOf(kept.id), obstacles.listByClub(club.id).map { it.id })
        assertEquals(gold + 7, clubs.rows.getValue(club.id).gold)
        // the log has every move, for a revert
        val moves = log.rows.associateBy { it.itemId }
        assertEquals(setOf(academy.id, court.id, office.id, road.id, fence.id, tree.id, house.id, ready.id, idle.id), moves.keys)
        assertEquals(34 to -4, moves.getValue(academy.id).let { it.oldX to it.oldY })
        assertEquals(7, moves.getValue(ready.id).gold); assertEquals(RehomeMove.OBSTACLE, moves.getValue(ready.id).kind)
        moves.getValue(fence.id).let { assertEquals(RehomeMove.REMOVED, it.kind); assertNull(it.newX); assertEquals(3, it.level); assertEquals(780L, it.coins); assertEquals(32 to -8, it.oldX to it.oldY) }
        assertEquals(40L, moves.getValue(road.id).coins)
        assertTrue(log.rows.all { it.maskId == TestLand.NOTCHED.id })
        // once: the next read moves nothing
        assertEquals(st.buildings.map { it.gridX to it.gridY }, get(user).buildings.map { it.gridX to it.gridY })
        assertEquals(9, log.rows.size)
        assertNull(notched.settle(UUID.randomUUID()))
    }

    @Test
    fun `a building the land has no room for waits parked, listed, never in the way of an edit, hidden from visitors`() = runBlocking<Unit> {
        // a tiny meadow: the stadium, the ticket office and a few strips of lawn round them, nowhere 8 cells deep (the strips
        // 6 deep since the 6×6 ticket office of 16 October)
        val tiny = LandMap.of(listOf(LandRegion.of("main", null, 8, 8, List(24) { "#".repeat(24) })), 10 to 10, 26 to 8)
        val small = service(tiny)
        val club = newClub()
        val training = at(club.id, BuildingType.TRAINING_CENTER, 22, 30)
        val academy = at(club.id, BuildingType.ACADEMY, 30, 30)
        val st = small.state(user)
        val parked = st.buildings.filter { Lands.isParked(it.gridY) }.associateBy { it.id }
        assertEquals(setOf(training.id, academy.id), parked.keys)
        // each on its own parking cell, in the order they were found lost (the seeded ticket office, off the tiny meadow,
        // was parked first for a moment and then found room)
        assertEquals(setOf(Lands.parked(1), Lands.parked(2)), parked.values.map { it.gridX to it.gridY }.toSet())
        assertFalse(Lands.isParked(st.buildings.single { it.type == BuildingType.TICKET_OFFICE }.gridY))
        assertEquals(2, log.rows.count { it.newX == null })
        // an edit that does not move them saves (they used to make every save fail), one onto the sea does not
        val office = st.buildings.single { it.type == BuildingType.TICKET_OFFICE }
        ApplyLayoutUseCase(small, buildings, clock)(user, listOf(Placement(office.id, 26, 14, 0)))
        assertEquals(14, buildings.findById(office.id)!!.gridY)
        assertEquals("Outside your land", assertFailsWith<ValidationException> { MoveBuildingUseCase(small, buildings, clock)(user, academy.id, 30, 30, 0) }.template)
        // a visitor sees the stadium and the office only, and the land they stand on
        val g = GetGroundsUseCase(clubs, buildings, com.arenacity.domain.usecase.athlete.SectionService(buildings, FakeAthleteRepository()), tiny)(club.id)
        assertEquals(setOf(BuildingType.PITCH, BuildingType.TICKET_OFFICE), g.buildings.map { it.type }.toSet())
        assertEquals(listOf("main"), g.land.ids)
        // a later mask with room puts them down
        val big = service(Lands.SQUARE)
        assertTrue(big.state(user).buildings.none { Lands.isParked(it.gridY) })
    }

    @Test
    fun `a layout saved before the mask skips the spots that are sea now`() = runBlocking<Unit> {
        val club = newClub()
        val square = service(Lands.SQUARE)
        val layouts = FakeLayoutRepository()
        val tree = at(club.id, BuildingType.DECOR, 34, -6)
        val bench = at(club.id, BuildingType.DECOR, 4, 28)
        LayoutUseCases(square, layouts, ApplyLayoutUseCase(square, buildings, clock), clock).save(user, 1, "Old")
        ApplyLayoutUseCase(square, buildings, clock)(user, listOf(Placement(bench.id, 6, 28, 0)))
        // the land becomes the notched mask: the tree moves out of the sea; the old layout puts the bench back, the tree stays
        val moved = get(user).buildings.single { it.id == tree.id }
        assertTrue(TestLand.NOTCHED.main.fits(fp(moved)))
        LayoutUseCases(notched, layouts, applyLayout, clock).applySlot(user, 1)
        assertEquals(4, buildings.findById(bench.id)!!.gridX)
        assertEquals(moved.gridX to moved.gridY, buildings.findById(tree.id)!!.let { it.gridX to it.gridY })
    }

    @Test
    fun `the start-up sweep settles every club not on the mask, once`() = runBlocking<Unit> {
        val a = newClub(); val b = newClub(UUID.randomUUID(), "Blue Lions")
        at(a.id, BuildingType.ACADEMY, 30, -8)
        val sweep = LandSweep(clubs, notched)
        assertEquals(2, sweep.run())
        assertTrue(listOf(a.id, b.id).all { clubs.rows.getValue(it).landMask == TestLand.NOTCHED.id })
        assertEquals(1, log.rows.size)
        assertEquals(0, sweep.run())
    }
}
