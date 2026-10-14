package com.arenacity.domain.usecase

import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.Building
import com.arenacity.domain.model.BuildingState
import com.arenacity.domain.model.Obstacle
import com.arenacity.domain.model.ObstacleState
import com.arenacity.domain.model.ZoneSport
import com.arenacity.domain.repository.RehomeMove
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.*
import com.arenacity.presentation.dto.toDto
import com.arenacity.testutil.*
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.*

/**
 * The sport town (the owner, 14 October 2026): the island arenas stand on their own side island only, once its zone
 * opened it (the server is the authority), and the club a migration left with grown footprints or arenas off their
 * islands settles: nothing overlapping, the arenas onto their islands or parked. On [TestLand.ORGANIC] (four islands).
 */
class SportTownTest {
    private val clock = FixedClock()
    private val clubs = FakeClubRepository()
    private val buildings = FakeBuildingRepository()
    private val obstacles = FakeObstacleRepository()
    private val zones = FakeZoneRepository()
    private val log = FakeRehomeLog()
    private val lands = TestLand.ORGANIC
    private val service = ClubService(clubs, buildings, clock, obstacles = obstacles, lands = lands, moves = log).apply { openZones = { id -> openZoneSports(zones, id, clock.now()) } }
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
    private fun isle(sport: ZoneSport) = lands.island(sport)!!
    /** A spot on a tile of [region] where any arena (12×10 at most) fits. */
    private fun spot(region: LandRegion) = region.cells.first { (x, y) -> Grid.aligned(x, y) && region.fits(Footprint(x, y, 12, 10)) }
    private fun at(clubId: UUID, type: BuildingType, x: Int, y: Int, level: Int = 1) = runBlocking {
        clock.advanceSeconds(60)   // created one after another: the older keeps its place
        buildings.create(Building(UUID.randomUUID(), clubId, type, level, x, y, 0, BuildingState.IDLE, null, clock.now(), clock.now(), clock.now()))
    }
    private fun fp(b: Building) = footprint(b.type, b.gridX, b.gridY, b.rotation)

    @Test
    fun `an arena is built on its own island only, once its zone opened it`() = runBlocking<Unit> {
        val club = newClub(10)
        val basket = isle(ZoneSport.BASKETBALL); val tennis = isle(ZoneSport.TENNIS)
        val (bx, by) = spot(basket)
        // the island still locked: the zone is not bought
        assertEquals("Open this island first", assertFailsWith<ValidationException> { place(user, BuildingType.BASKETBALL_ARENA, bx, by, 0) }.template)
        // on the main island, or on another sport's island (even an open one), it is never built
        val (mx, my) = lands.main.cells.first { (x, y) -> Grid.aligned(x, y) && lands.main.fits(Footprint(x, y, 10, 10)) && buildings.listByClub(club.id).none { fp(it).overlaps(Footprint(x, y, 10, 10)) } }
        assertEquals("This arena is built on its own island", assertFailsWith<ValidationException> { place(user, BuildingType.BASKETBALL_ARENA, mx, my, 0) }.template)
        zones.open(club.id, ZoneSport.TENNIS)
        val (tx, ty) = spot(tennis)
        assertEquals("This arena is built on its own island", assertFailsWith<ValidationException> { place(user, BuildingType.BASKETBALL_ARENA, tx, ty, 0) }.template)
        // half off its island
        assertEquals("This arena is built on its own island", assertFailsWith<ValidationException> { place(user, BuildingType.BASKETBALL_ARENA, Math.floorDiv(basket.x0 + basket.w - 4, 2) * 2, by, 0) }.template)
        // the zone open: built there, and only there
        zones.open(club.id, ZoneSport.BASKETBALL)
        val arena = place(user, BuildingType.BASKETBALL_ARENA, bx, by, 0).let { speed(user, it.id) }
        assertTrue(basket.fits(fp(arena)))
        assertEquals("This arena is built on its own island", assertFailsWith<ValidationException> { move(user, arena.id, mx, my, 0) }.template)
        assertEquals("This arena is built on its own island", assertFailsWith<ValidationException> { layout(user, listOf(Placement(arena.id, tx, ty, 0))) }.template)
        // the tennis arena on its island; any other building may still stand on an open island
        assertTrue(tennis.fits(fp(place(user, BuildingType.TENNIS_ARENA, tx, ty, 0).let { speed(user, it.id) })))
        val hut = tennis.cells.first { (x, y) -> Grid.aligned(x, y) && tennis.fits(Footprint(x, y, 4, 4)) && buildings.listByClub(club.id).none { fp(it).overlaps(Footprint(x - 1, y - 1, 6, 6)) } }
        place(user, BuildingType.SCOUTING_OFFICE, hut.first, hut.second, 0)
        // the stadium still caps it: padel opens at stadium 9
        buildings.setStadium(club.id, 8); zones.open(club.id, ZoneSport.PADEL)
        val (px, py) = spot(isle(ZoneSport.PADEL))
        assertEquals("Requires stadium level {0}", assertFailsWith<ValidationException> { place(user, BuildingType.PADEL_ARENA, px, py, 0) }.template)
    }

    @Test
    fun `the arena opens its section like the court did, and the island with its zone`() = runBlocking<Unit> {
        val club = newClub(3)
        val athletes = FakeAthleteRepository()
        val sections = com.arenacity.domain.usecase.athlete.SectionService(buildings, athletes)
        val withSquad = ClubService(clubs, buildings, clock, listOf(com.arenacity.domain.usecase.athlete.SectionSquadOnCourt(athletes, clock)), obstacles = obstacles, lands = lands)
            .apply { openZones = { id -> openZoneSports(zones, id, clock.now()) } }
        zones.open(club.id, ZoneSport.BASKETBALL)
        val (bx, by) = spot(isle(ZoneSport.BASKETBALL))
        val arena = PlaceBuildingUseCase(withSquad, buildings, clock)(user, BuildingType.BASKETBALL_ARENA, bx, by, 0)
        SpeedUpUseCase(withSquad, buildings, clock)(user, arena.id)
        assertEquals(8, athletes.rows.values.count { it.clubId == club.id && it.sport == com.arenacity.domain.model.Sport.BASKETBALL })
        assertTrue(com.arenacity.domain.model.Sport.BASKETBALL in sections.active(clubs.rows.getValue(club.id)))
        assertEquals(60, TrainingRules.attributeCap(com.arenacity.domain.model.Sport.BASKETBALL, 1))
    }

    @Test
    fun `a club with grown footprints and arenas off their islands settles once`() = runBlocking<Unit> {
        val club = newClub(10)
        val main = lands.main
        // what the migration leaves (V36): a shopping centre that grew onto a sponsor office, a road tile and a tree, and a
        // bush beside it; two old courts turned arenas standing on the main island — basketball's zone open, tennis' not
        val shop = at(club.id, BuildingType.SHOPPING_CENTER, 24, 22, level = 6)
        val sponsor = at(club.id, BuildingType.SPONSOR_OFFICE, 28, 24, level = 2)
        val road = at(club.id, BuildingType.ROAD, 24, 26)
        val tree = at(club.id, BuildingType.DECOR, 26, 24)
        val court = at(club.id, BuildingType.BACKYARD_COURT, 24, 30, level = 4)
        val basket = at(club.id, BuildingType.BASKETBALL_ARENA, 0, 24, level = 4)
        val tennis = at(club.id, BuildingType.TENNIS_ARENA, 12, 32, level = 2)
        for (b in listOf(shop, sponsor, road, tree, court, basket, tennis)) assertTrue(main.fits(fp(b)), "${b.type} starts on the meadow")
        val bush = Obstacle(UUID.randomUUID(), club.id, ObstacleKind.BUSH, 27, 23, ObstacleState.READY, null, 6, clock.now(), clock.now())
        obstacles.create(bush)
        zones.open(club.id, ZoneSport.BASKETBALL)
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
        // the basketball arena on its island at its level; the tennis arena parked off the land (its island closed), its
        // section still open
        assertTrue(isle(ZoneSport.BASKETBALL).fits(fp(byId.getValue(basket.id)))); assertEquals(4, byId.getValue(basket.id).level)
        assertTrue(Lands.isParked(byId.getValue(tennis.id).gridY))
        assertTrue(com.arenacity.domain.rules.Sections.venueOperational(byId.getValue(tennis.id)))
        // logged for a revert
        val moves = log.rows.associateBy { it.itemId }
        assertEquals(setOf(sponsor.id, road.id, tree.id, basket.id, tennis.id, bush.id), moves.keys)
        assertEquals(RehomeMove.REMOVED, moves.getValue(road.id).kind)
        assertNull(moves.getValue(tennis.id).newX)
        // once: the next read moves nothing
        val size = log.rows.size
        service.state(user)
        assertEquals(size, log.rows.size)
        // the player opens the tennis island and puts the parked arena there by hand
        zones.open(club.id, ZoneSport.TENNIS)
        val (tx, ty) = spot(isle(ZoneSport.TENNIS))
        assertTrue(isle(ZoneSport.TENNIS).fits(fp(move(user, tennis.id, tx, ty, 0))))
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
        val arena = BuildingCatalog.spec(BuildingType.TENNIS_ARENA).toDto("en")
        assertEquals("TENNIS", arena.island); assertEquals("TENNIS", arena.section); assertFalse(arena.soon)
        val volley = BuildingCatalog.spec(BuildingType.VOLLEYBALL_ARENA).toDto("es")
        assertEquals("VOLLEYBALL", volley.island); assertNull(volley.section); assertTrue(volley.soon); assertEquals("Pronto", volley.soonText)
        assertEquals("Abrirá la sección de voleibol.", volley.about)
        assertEquals("FOOTBALL", BuildingCatalog.spec(BuildingType.PITCH).toDto().section)
    }
}
