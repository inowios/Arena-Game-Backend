package com.arenacity.it

import com.arenacity.common.tx.DbTx
import com.arenacity.data.repository.*
import com.arenacity.domain.model.BuildingState
import com.arenacity.domain.model.ZoneSport
import com.arenacity.domain.rules.*
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.club.LandSweep
import com.arenacity.domain.usecase.club.openZoneSports
import com.arenacity.testutil.FixedClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID
import kotlin.test.*

/**
 * The sport town's migration (V36__Sport_town.sql) on a club built with the old buildings, then the start-up sweep that
 * settles it (ClubService.settle): the old types become the new ones, what goes is refunded and logged, the grown
 * footprints and the arenas move.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class SportTownMigrationIT {
    private val clock = FixedClock()

    @Test
    fun `the old buildings become the sport town, refunded and logged, then settle onto the land and the islands`() = runBlocking<Unit> {
        val ds = TestDb.fresh(target = "35")
        val club = UUID.randomUUID(); val other = UUID.randomUUID()
        val ids = mutableMapOf<String, UUID>()
        val sql = StringBuilder()
        for ((c, name) in listOf(club to "Old Town", other to "Tidy Town")) {
            val user = UUID.randomUUID()
            sql.append("INSERT INTO users (id, auth_provider, provider_id) VALUES ('$user', 'GUEST', 'it-$user');\n")
            sql.append("INSERT INTO clubs (id, user_id, name, primary_color, secondary_color, coins, land_mask) VALUES ('$c', '$user', '$name', '#AA0000', '#FFFFFF', 1000, '${Lands.MAP.id}');\n")
        }
        var minute = 0
        fun b(key: String, c: UUID, type: String, level: Int, x: Int, y: Int) {
            val id = UUID.randomUUID(); ids[key] = id
            // created a minute apart in this order: the older keeps its place when two grown footprints meet
            sql.append("INSERT INTO buildings (id, club_id, type, level, grid_x, grid_y, created_at) VALUES ('$id', '$c', '$type', $level, $x, $y, TIMESTAMPTZ '2026-10-01 00:00:00+00' + INTERVAL '${minute++} minutes');\n")
        }
        b("pitch", club, "PITCH", 10, 11, 12); b("ticket", club, "TICKET_OFFICE", 3, 9, 9)
        // the shop (4×3) and the café (3×3) side by side: both 5×4 now, they would overlap; the car park the lowest of the three
        b("shop", club, "CLUB_SHOP", 5, 20, 20); b("cafe", club, "CAFE", 7, 24, 20); b("parking", club, "PARKING", 3, 20, 26)
        b("road", club, "ROAD", 1, 22, 23)    // under the grown shop
        b("museum", club, "MUSEUM", 2, 26, 26)
        for (i in 1..6) b("park$i", club, "PARK", i, if (i % 2 == 1) 0 else 5, 15 + 5 * ((i - 1) / 2))
        b("basket", club, "BASKETBALL_COURT", 4, 2, 36); b("tennis", club, "TENNIS_COURT", 2, 8, 36)
        b("apartment", club, "APARTMENT", 3, 14, 36); b("office", club, "OFFICE", 1, 20, 34)
        b("pitch2", other, "PITCH", 3, 11, 12); b("academy2", other, "ACADEMY", 2, 20, 20)
        // a stadium-1 town laid out before the footprints grew (no old type): its academy now reaches under the stadium
        val crowded = UUID.randomUUID(); val showcase = UUID.randomUUID()
        for ((c, name, bucket) in listOf(Triple(crowded, "Crowded", 0), Triple(showcase, "Show Town", -1))) {
            val user = UUID.randomUUID()
            sql.append("INSERT INTO users (id, auth_provider, provider_id) VALUES ('$user', 'GUEST', 'it-$user');\n")
            sql.append("INSERT INTO clubs (id, user_id, name, primary_color, secondary_color, coins, land_mask, tz_bucket) VALUES ('$c', '$user', '$name', '#AA0000', '#FFFFFF', 1000, '${Lands.MAP.id}', $bucket);\n")
        }
        b("pitch3", crowded, "PITCH", 1, 11, 12); b("academy3", crowded, "ACADEMY", 1, 11, 9)
        b("pitch4", showcase, "PITCH", 5, 11, 12)
        // the basketball zone of the club is open (its island too), the tennis zone is not bought
        sql.append("INSERT INTO club_zones (club_id, sport, state, bought_at, restore_ends_at) VALUES ('$club', 'BASKETBALL', 'OPEN', now(), NULL);\n")
        // an alliance asked to help with the apartment (it goes) and the café (it changes type)
        val alliance = UUID.randomUUID()
        sql.append("INSERT INTO alliances (id, name, tag, created_by) VALUES ('$alliance', 'Helpers', 'HLP', '$club');\n")
        sql.append("INSERT INTO alliance_helps (id, alliance_id, club_id, building_id, building_type, level, max_helps) VALUES " +
            "('${UUID.randomUUID()}', '$alliance', '$club', '${ids["apartment"]}', 'APARTMENT', 3, 5), ('${UUID.randomUUID()}', '$alliance', '$club', '${ids["cafe"]}', 'CAFE', 7, 5);\n")
        ds.connection.use { c -> c.createStatement().execute(sql.toString()); c.commit() }

        TestDb.migrate(ds, target = "36")

        fun <T> query(q: String, read: (java.sql.ResultSet) -> T): List<T> = ds.connection.use { c ->
            c.createStatement().executeQuery(q).use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        }
        val types = query("SELECT id, type, level FROM buildings WHERE club_id = '$club'") { UUID.fromString(it.getString(1)) to (it.getString(2) to it.getInt(3)) }.toMap()
        // the shop and the café (the two highest) are shopping centres at their levels, the car park went
        assertEquals("SHOPPING_CENTER" to 5, types[ids["shop"]]); assertEquals("SHOPPING_CENTER" to 7, types[ids["cafe"]])
        assertNull(types[ids["parking"]])
        assertEquals("HALL_OF_FAME" to 2, types[ids["museum"]])
        // the four highest parks are yard courts, the two lowest went
        assertEquals((3..6).map { "BACKYARD_COURT" to it }.toSet(), (1..6).mapNotNull { types[ids["park$it"]] }.toSet())
        assertEquals("BASKETBALL_ARENA" to 4, types[ids["basket"]]); assertEquals("TENNIS_ARENA" to 2, types[ids["tennis"]])
        assertNull(types[ids["apartment"]]); assertNull(types[ids["office"]])
        assertEquals("PITCH" to 10, types[ids["pitch"]]); assertEquals("TICKET_OFFICE" to 3, types[ids["ticket"]])
        // refunded at what their levels cost: the car park (4 500 + 8 000 + 14 000), the parks of levels 1 and 2 (5 000;
        // 5 000 + 12 000), the apartment (9 000 + 15 000 + 25 000) and the office (16 000)
        val refund = 26_500L + 5_000 + 17_000 + 49_000 + 16_000
        assertEquals(1_000 + refund, query("SELECT coins FROM clubs WHERE id = '$club'") { it.getLong(1) }.single())
        val log = query("SELECT kind, type, level, coins, new_x FROM rehome_log WHERE club_id = '$club' AND mask_id = 'sport_town'") {
            listOf(it.getString(1), it.getString(2), it.getInt(3), it.getLong(4), it.getObject(5))
        }
        assertEquals(9, log.count { it[0] == "RETYPED" }); assertEquals(5, log.count { it[0] == "REMOVED" })
        assertEquals(refund, log.filter { it[0] == "REMOVED" }.sumOf { it[3] as Long })
        assertTrue(log.filter { it[0] == "REMOVED" }.all { it[4] == null })
        assertEquals(listOf("APARTMENT", 3, 49_000L), log.single { it[1] == "APARTMENT" }.subList(1, 4))
        // the help for the apartment is closed, the café's names the shopping centre
        assertEquals(setOf("APARTMENT" to true, "SHOPPING_CENTER" to false), query("SELECT building_type, closed FROM alliance_helps") { it.getString(1) to it.getBoolean(2) }.toSet())
        // the club touched settles again at the next start; the other one stays as it was
        assertEquals(listOf<String?>(null), query("SELECT land_mask FROM clubs WHERE id = '$club'") { it.getString(1) })
        assertEquals(listOf<String?>(Lands.MAP.id), query("SELECT land_mask FROM clubs WHERE id = '$other'") { it.getString(1) })

        // the start-up sweep
        val clubs = ClubRepositoryImpl(); val buildings = BuildingRepositoryImpl(); val zones = ZoneRepositoryImpl()
        val service = ClubService(clubs, buildings, clock, obstacles = ObstacleRepositoryImpl(), tx = DbTx, moves = RehomeLogRepositoryImpl())
            .apply { openZones = { id -> openZoneSports(zones, id, clock.now()) } }
        assertEquals(1, LandSweep(clubs, service).run { _, e -> throw e })
        assertEquals(Lands.MAP.id, clubs.findById(club)!!.landMask)
        val list = buildings.listByClub(club)
        val land = Lands.MAP.of(setOf(ZoneSport.BASKETBALL))
        val placed = list.filter { !Lands.isParked(it.gridY) }
        assertTrue(placed.all { Lands.MAP.allows(land, it.type, footprint(it.type, it.gridX, it.gridY, it.rotation)) }, "everything stands where it may")
        val fps = placed.map { footprint(it.type, it.gridX, it.gridY, it.rotation) }
        for (i in fps.indices) for (j in i + 1 until fps.size) assertFalse(fps[i].overlaps(fps[j]), "${placed[i].type} overlaps ${placed[j].type}")
        // the shop (older) kept its place, the café moved off it; the road tile under the shop went, refunded
        val byId = list.associateBy { it.id }
        assertEquals(20 to 20, byId.getValue(ids["shop"]!!).let { it.gridX to it.gridY })
        assertNotEquals(24 to 20, byId.getValue(ids["cafe"]!!).let { it.gridX to it.gridY })
        assertNull(byId[ids["road"]])
        assertEquals(1_000 + refund + BuildingCatalog.level(BuildingType.ROAD, 1).cost, clubs.findById(club)!!.coins)
        // the basketball arena on its island (the zone is open), the tennis arena parked until the player opens its island
        val basket = byId.getValue(ids["basket"]!!)
        assertTrue(Lands.MAP.island(ZoneSport.BASKETBALL)!!.fits(footprint(basket.type, basket.gridX, basket.gridY, basket.rotation)))
        val tennis = byId.getValue(ids["tennis"]!!)
        assertTrue(Lands.isParked(tennis.gridY))
        assertTrue(Sections.venueOperational(tennis) && tennis.state == BuildingState.IDLE, "the tennis section stays open")
        // once: a second sweep finds nothing to settle
        assertEquals(0, LandSweep(clubs, service).run())

        // V38: every town but the showcases settles once more — a clean one stays exactly as it is, the crowded one moves
        // its academy off the stadium (V37 before it moves the club's seeded ticket office in front of the stadium)
        TestDb.migrate(ds, target = "37")
        val before = listOf(club, other).associateWith { c -> buildings.listByClub(c).map { Triple(it.id, it.gridX, it.gridY) }.toSet() }
        TestDb.migrate(ds)
        assertEquals(listOf<String?>(Lands.MAP.id), query("SELECT land_mask FROM clubs WHERE id = '$showcase'") { it.getString(1) })
        assertEquals(3, LandSweep(clubs, service).run { _, e -> throw e })
        for ((c, was) in before) assertEquals(was, buildings.listByClub(c).map { Triple(it.id, it.gridX, it.gridY) }.toSet(), "a clean town stays")
        val town = buildings.listByClub(crowded)
        val pitch3 = town.single { it.type == BuildingType.PITCH }; val academy3 = town.single { it.type == BuildingType.ACADEMY }
        assertEquals(11 to 12, pitch3.gridX to pitch3.gridY, "the stadium keeps its place")
        assertFalse(footprint(academy3.type, academy3.gridX, academy3.gridY, academy3.rotation).overlaps(footprint(pitch3.type, 11, 12, 0)))
        assertTrue(Lands.MAP.allows(Lands.MAP.home, academy3.type, footprint(academy3.type, academy3.gridX, academy3.gridY, academy3.rotation)))
        assertEquals(0, LandSweep(clubs, service).run())
    }
}
