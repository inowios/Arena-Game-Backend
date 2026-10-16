package com.arenacity.it

import com.arenacity.domain.rules.BuildingType
import com.arenacity.domain.rules.Footprint
import com.arenacity.domain.rules.Lands
import com.arenacity.domain.rules.footprint
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID
import kotlin.test.*

/**
 * The first ticket office in front of the stadium (V37__Ticket_office_in_front.sql): a club still laid out as the seeding
 * left it gets its ticket office moved from behind the stadium to the land mask's new anchor, logged; a club whose cells
 * in front are taken, or whose player moved or turned either building, keeps its town.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class TicketFrontMigrationIT {

    @Test
    fun `the seeded ticket office moves in front of the stadium only where the town is still as seeded and the spot is free`() = runBlocking<Unit> {
        val ds = TestDb.fresh(target = "36")
        val sql = StringBuilder()
        val ticket = mutableMapOf<String, UUID>()
        fun club(name: String, pitch: Pair<Int, Int> = 11 to 12, office: Pair<Int, Int> = 9 to 9, officeRotation: Int = 0): UUID {
            val user = UUID.randomUUID(); val c = UUID.randomUUID()
            sql.append("INSERT INTO users (id, auth_provider, provider_id) VALUES ('$user', 'GUEST', 'it-$user');\n")
            sql.append("INSERT INTO clubs (id, user_id, name, primary_color, secondary_color) VALUES ('$c', '$user', '$name', '#AA0000', '#FFFFFF');\n")
            sql.append("INSERT INTO buildings (id, club_id, type, level, grid_x, grid_y) VALUES ('${UUID.randomUUID()}', '$c', 'PITCH', 4, ${pitch.first}, ${pitch.second});\n")
            val t = UUID.randomUUID(); ticket[name] = t
            sql.append("INSERT INTO buildings (id, club_id, type, level, grid_x, grid_y, rotation) VALUES ('$t', '$c', 'TICKET_OFFICE', 2, ${office.first}, ${office.second}, $officeRotation);\n")
            return c
        }
        fun building(c: UUID, type: String, x: Int, y: Int, rotation: Int = 0) =
            sql.append("INSERT INTO buildings (id, club_id, type, level, grid_x, grid_y, rotation) VALUES ('${UUID.randomUUID()}', '$c', '$type', 1, $x, $y, $rotation);\n")
        val seeded = club("Seeded")
        building(seeded, "HOUSE", 20, 18)                     // next to the spot (x 20..22), not on it
        building(seeded, "ACADEMY", 14, 20)                   // just in front of it (y 20..23)
        club("House on it").let { building(it, "HOUSE", 17, 17) }                 // x 17..19, y 17..19: on the spot's corner
        club("Turned court").let { building(it, "BACKYARD_COURT", 12, 19, 90) }   // 3x3 turned, y 19..21: on its front row
        club("Road on it").let { building(it, "ROAD", 18, 19) }
        club("Shed on it").let { c -> sql.append("INSERT INTO obstacles (id, club_id, kind, grid_x, grid_y, reward_gold) VALUES ('${UUID.randomUUID()}', '$c', 'SHED', 13, 17, 5);\n") }
        club("Bush by it").let { c -> sql.append("INSERT INTO obstacles (id, club_id, kind, grid_x, grid_y, reward_gold) VALUES ('${UUID.randomUUID()}', '$c', 'BUSH', 19, 18, 5);\n") }
        club("Office moved", office = 25 to 25)
        club("Office turned", officeRotation = 90)
        club("Stadium moved", pitch = 11 to 14)
        ds.connection.use { c -> c.createStatement().execute(sql.toString()); c.commit() }

        TestDb.migrate(ds)

        fun <T> query(q: String, read: (java.sql.ResultSet) -> T): List<T> = ds.connection.use { c ->
            c.createStatement().executeQuery(q).use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        }
        val at = query("SELECT id, grid_x, grid_y FROM buildings WHERE type = 'TICKET_OFFICE'") { UUID.fromString(it.getString(1)) to (it.getInt(2) to it.getInt(3)) }.toMap()
        val moved = setOf("Seeded", "Bush by it")
        for ((name, id) in ticket) {
            val want = when (name) { in moved -> 14 to 18; "Office moved" -> 25 to 25; else -> 9 to 9 }
            assertEquals(want, at[id], name)
        }
        // V37's spot was the land mask's anchor then; since the 12×9 stadium (14 October, pass 2) a new club's office stands
        // at 3, 22 — on the main island, beside the stadium on the screen with lawn round it (at 16, 21 it touched the
        // stadium and its drawing covered the near stand) — and the start-up sweep moves an office V37 left under it
        // (on the build grid's tiles since 14 October, pass 3: the office 4×4 at 4, 22, the 12×12 stadium at 10, 10)
        assertEquals(2 to 22, Lands.MAP.ticket)
        val office = footprint(BuildingType.TICKET_OFFICE, 2, 22, 0)
        assertTrue(Lands.MAP.main.fits(office))
        val pitch = footprint(BuildingType.PITCH, Lands.MAP.stadium.first, Lands.MAP.stadium.second, 0)
        assertFalse(Footprint(office.x - 1, office.y - 1, office.w + 2, office.h + 2).overlaps(pitch))
        // logged, so a revert can put them back
        val log = query("SELECT item_id, kind, type, old_x, old_y, new_x, new_y, level FROM rehome_log WHERE mask_id = 'ticket_front'") {
            listOf(it.getString(1), it.getString(2), it.getString(3), it.getInt(4), it.getInt(5), it.getInt(6), it.getInt(7), it.getInt(8))
        }
        assertEquals(moved.map { ticket.getValue(it).toString() }.toSet(), log.map { it[0] }.toSet())
        assertTrue(log.all { it.drop(1) == listOf("BUILDING", "TICKET_OFFICE", 9, 9, 14, 18, 2) }, log.toString())
    }
}
