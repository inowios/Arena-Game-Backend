package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.Building
import com.arenacity.domain.model.BuildingState
import com.arenacity.domain.model.Obstacle
import com.arenacity.domain.model.ObstacleState
import com.arenacity.domain.model.People
import com.arenacity.domain.repository.RehomeMove
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.presentation.dto.toDto
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.*

/**
 * The sport town (the owner, 14 October 2026) and the peoples' islands (15 October: no arenas on them any more): a
 * people's island is the club's land once its bridge stands (the server is the authority), and the club a migration left
 * with grown footprints settles: nothing overlapping. On [TestLand.ORGANIC] (four islands).
 */
class SportTownTest {
    private val clock = FixedClock()
    private val clubs = FakeClubRepository()
    private val buildings = FakeBuildingRepository()
    private val obstacles = FakeObstacleRepository()
    private val zones = FakeZoneRepository()
    private val log = FakeRehomeLog()
    private val lands = TestLand.ORGANIC
    private val service = ClubService(clubs, buildings, clock, obstacles = obstacles, lands = lands, moves = log).apply { openZones = { id -> openPeoples(zones, id, clock.now()) } }
    private val create = CreateClubUseCase(clubs, buildings, clock, emptyList(), lands = lands)
    private val place = PlaceBuildingUseCase(service, buildings, clock)
    private val move = MoveBuildingUseCase(service, buildings, clock)
    private val speed = SpeedUpUseCase(service, buildings, clock)
    private val layout = ApplyLayoutUseCase(service, buildings, clock)
    private val user = UUID.randomUUID()

    private fun newClub(stadium: Int) = runBlocking {
        val c = create(user, "Island FC", "#AA0000", "#FFFFFF", "UTC")
        clubs.update(clubs.rows.getValue(c.id).copy(coins = 10_000_000, gold = 10_000, trophies = 100_000))
        obstacles.clocks[c.id] = clock.now()
        buildings.setStadium(c.id, stadium)
        clubs.rows.getValue(c.id)
    }
    private fun isle(people: People) = lands.island(people)!!
    /** A spot on a tile of [region] where a [w] × [h] building fits. */
    private fun spot(region: LandRegion, w: Int = 6, h: Int = 6) = region.cells.first { (x, y) -> Grid.aligned(x, y) && region.fits(Footprint(x, y, w, h)) }
    private fun at(clubId: UUID, type: BuildingType, x: Int, y: Int, level: Int = 1) = runBlocking {
        clock.advanceSeconds(60)   // created one after another: the older keeps its place
        buildings.create(Building(UUID.randomUUID(), clubId, type, level, x, y, 0, BuildingState.IDLE, null, clock.now(), clock.now(), clock.now()))
    }
    private fun fp(b: Building) = footprint(b.type, b.gridX, b.gridY, b.rotation)

    @Test
    fun `a people's island is built on once its bridge stands`() = runBlocking<Unit> {
        val club = newClub(10)
        val beast = isle(People.BEAST)
        val (bx, by) = spot(beast)
        // the bridge not built: the island is locked
        assertEquals("The island opens with its bridge", assertFailsWith<ValidationException> { place(user, BuildingType.SHOPPING_CENTER, bx, by, 0) }.template)
        // the bridge built: any building may stand there
        zones.open(club.id, People.BEAST)
        assertTrue(beast.fits(fp(place(user, BuildingType.SHOPPING_CENTER, bx, by, 0))))
        // the other islands stay locked
        val (sx, sy) = spot(isle(People.SHADOW))
        assertEquals("The island opens with its bridge", assertFailsWith<ValidationException> { place(user, BuildingType.SCOUTING_OFFICE, sx, sy, 0) }.template)
        // football is the one section: no island opens another
        val sections = com.arenacity.domain.usecase.athlete.SectionService(buildings, FakeAthleteRepository())
        assertEquals(listOf(com.arenacity.domain.model.Sport.FOOTBALL), Sections.ORDER)
        assertTrue(sections.active(clubs.rows.getValue(club.id)).all { it == com.arenacity.domain.model.Sport.FOOTBALL })
    }

    @Test
    fun `a club with grown footprints settles once`() = runBlocking<Unit> {
        val club = newClub(10)
        val main = lands.main
        // what the migration leaves (V36): a shopping centre that grew onto a sponsor office, a road tile and a tree, and a
        // bush beside it
        val shop = at(club.id, BuildingType.SHOPPING_CENTER, 24, 22, level = 6)
        val sponsor = at(club.id, BuildingType.SPONSOR_OFFICE, 28, 24, level = 2)
        val road = at(club.id, BuildingType.ROAD, 24, 26)
        val tree = at(club.id, BuildingType.DECOR, 26, 24)
        val court = at(club.id, BuildingType.BACKYARD_COURT, 24, 30, level = 4)
        for (b in listOf(shop, sponsor, road, tree, court)) assertTrue(main.fits(fp(b)), "${b.type} starts on the meadow")
        val bush = Obstacle(UUID.randomUUID(), club.id, ObstacleKind.BUSH, 27, 23, ObstacleState.READY, null, 6, clock.now(), clock.now())
        obstacles.create(bush)
        zones.open(club.id, People.BEAST)
        clubs.rows[club.id] = clubs.rows.getValue(club.id).copy(landMask = null)
        val coins = clubs.rows.getValue(club.id).coins; val gold = clubs.rows.getValue(club.id).gold

        val st = service.state(user)
        val byId = st.buildings.associateBy { it.id }
        val placed = st.buildings.filter { !Lands.isParked(it.gridY) }
        assertTrue(placed.all { lands.allows(st.land, it.type, fp(it)) }, "everything stands where it may")
        for (i in placed.indices) for (j in i + 1 until placed.size) assertFalse(fp(placed[i]).overlaps(fp(placed[j])), "${placed[i].type} overlaps ${placed[j].type}")
        // the bigger shop keeps its place, the sponsor office moves at its level; the road tile under the shop goes,
        // refunded; the tree moves; the bush under the shop goes, its gold the club's
        assertEquals(24 to 22, byId.getValue(shop.id).let { it.gridX to it.gridY })
        assertNotEquals(28 to 24, byId.getValue(sponsor.id).let { it.gridX to it.gridY }); assertEquals(2, byId.getValue(sponsor.id).level)
        assertNull(byId[road.id])
        assertEquals(coins + BuildingCatalog.level(BuildingType.ROAD, 1).cost, clubs.rows.getValue(club.id).coins)
        assertFalse(fp(byId.getValue(tree.id)).overlaps(fp(byId.getValue(shop.id))))
        assertTrue(obstacles.rows.isEmpty()); assertEquals(gold + 6, clubs.rows.getValue(club.id).gold)
        assertEquals(24 to 30, byId.getValue(court.id).let { it.gridX to it.gridY })
        // logged for a revert
        val moves = log.rows.associateBy { it.itemId }
        assertEquals(setOf(sponsor.id, road.id, tree.id, bush.id), moves.keys)
        assertEquals(RehomeMove.REMOVED, moves.getValue(road.id).kind)
        // once: the next read moves nothing
        val size = log.rows.size
        service.state(user)
        assertEquals(size, log.rows.size)
        // the player opens the androids' island and moves the sponsor office there by hand
        zones.open(club.id, People.ANDROID)
        val (tx, ty) = spot(isle(People.ANDROID))
        assertTrue(isle(People.ANDROID).fits(fp(move(user, sponsor.id, tx, ty, 0))))
    }

    @Test
    fun `the catalog names every building and says what the ones to come will do`() {
        for (lang in com.arenacity.common.i18n.ServerI18n.LANGS) for (s in BuildingCatalog.specs.values) {
            val d = s.toDto(lang)
            assertNotEquals("building.${s.type}", d.name, "${s.type} has no name in $lang")
            if (s.soon) { assertNotNull(d.about, "${s.type}: what it will do, in $lang"); assertNotNull(d.soonText) } else assertNull(d.soonText)
        }
        val ru = BuildingCatalog.spec(BuildingType.ANALYTICS_CENTER).toDto("ru")
        assertEquals("Аналитический центр", ru.name); assertEquals("Скоро", ru.soonText); assertTrue(ru.soon)
        assertEquals("Pronto", BuildingCatalog.spec(BuildingType.SCHOOL).toDto("es").soonText)
        assertTrue(BuildingCatalog.specs.values.all { it.toDto().island == null }, "no building has an island of its own")
        assertEquals("FOOTBALL", BuildingCatalog.spec(BuildingType.PITCH).toDto().section)
    }
}
