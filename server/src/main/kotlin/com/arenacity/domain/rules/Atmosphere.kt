package com.arenacity.domain.rules

import com.arenacity.domain.model.Building
import java.util.UUID

/**
 * Atmosphere (like land value in SimCity): where things stand matters. An earning building earns more with decor around
 * it, next to the stadium (the ticket office, the shopping centre and the fan zone live off the crowd), with the hall of
 * fame's visitors and a yard court nearby; a tidy fence line around it adds a little more. Distances are gaps between
 * footprints in cells (0 = touching). Total bonus capped at [CAP] %.
 */
object Atmosphere {
    const val CAP = 48
    const val DECOR_PCT = 3; const val DECOR_RANGE = 2; const val DECOR_CAP = 24
    const val STADIUM_PCT = 10; const val STADIUM_RANGE = 3
    const val FENCE_PCT = 1; const val FENCE_RANGE = 1; const val FENCE_CAP = 8
    /** A yard court lifts the earning buildings within this many cells by its level's effect2 (the best one nearby). */
    const val COURT_RANGE = 3
    /** The buildings that earn (the homes' tax aside): they get the bonus. */
    private val incomeTypes = setOf(BuildingType.TICKET_OFFICE, BuildingType.SHOPPING_CENTER, BuildingType.FAN_ZONE, BuildingType.MEDIA_CENTER,
        BuildingType.SPONSOR_OFFICE, BuildingType.HOTEL)
    private val crowdTypes = setOf(BuildingType.TICKET_OFFICE, BuildingType.SHOPPING_CENTER, BuildingType.FAN_ZONE)

    /** Cells between two footprints along the worse axis (0 when they touch or overlap). */
    fun gap(a: Footprint, b: Footprint): Int {
        val dx = maxOf(0, maxOf(a.x - (b.x + b.w), b.x - (a.x + a.w)))
        val dy = maxOf(0, maxOf(a.y - (b.y + b.h), b.y - (a.y + a.h)))
        return maxOf(dx, dy)
    }

    /**
     * [museum]: visitors of the hall of fame nearby; [court]: a yard court nearby; [collection]: complete cosmetic sets
     * (club-wide, above the cap); [event]: a running live event's extra income % (club-wide, above the cap like
     * collections). [parking]: the old car park's, always 0 since the sport town (kept for the client's field).
     */
    data class Bonus(val decor: Int = 0, val stadium: Int = 0, val parking: Int = 0, val fence: Int = 0, val museum: Int = 0, val collection: Int = 0, val event: Int = 0,
                     val court: Int = 0) {
        val total get() = minOf(CAP, decor + stadium + parking + fence + museum + court) + collection + event
    }

    /** The bonus a [type] building at [fp] would get among [others] (its own row excluded by the caller). */
    fun bonusAt(type: BuildingType, fp: Footprint, others: List<Pair<BuildingType, Footprint>>): Bonus {
        if (type !in incomeTypes) return Bonus()
        val decor = minOf(DECOR_CAP, others.count { it.first == BuildingType.DECOR && gap(fp, it.second) <= DECOR_RANGE } * DECOR_PCT)
        val stadium = if (type in crowdTypes && others.any { it.first == BuildingType.PITCH && gap(fp, it.second) <= STADIUM_RANGE }) STADIUM_PCT else 0
        val fence = minOf(FENCE_CAP, others.count { it.first == BuildingType.FENCE && gap(fp, it.second) <= FENCE_RANGE } * FENCE_PCT)
        return Bonus(decor, stadium, 0, fence)
    }

    /** Bonus of every building of a club (only income buildings get one); [collectionPct] from complete sets. */
    fun of(buildings: List<Building>, collectionPct: Int = 0, eventPct: Int = 0): Map<UUID, Bonus> {
        val fps = buildings.associate { it.id to footprint(it.type, it.gridX, it.gridY, it.rotation) }
        // a hall of fame or a yard court under construction keeps the level it had (none while its first one is built)
        fun working(b: Building) = if (b.state == com.arenacity.domain.model.BuildingState.UPGRADING) b.level - 1 else b.level
        val halls = buildings.filter { it.type == BuildingType.HALL_OF_FAME && it.level > 0 }
        val courts = buildings.filter { it.type == BuildingType.BACKYARD_COURT && working(it) > 0 }
        return buildings.associate { b ->
            var bonus = bonusAt(b.type, fps.getValue(b.id), buildings.filter { it.id != b.id }.map { it.type to fps.getValue(it.id) })
            if (b.type in incomeTypes) {
                val m = halls.filter { gap(fps.getValue(b.id), fps.getValue(it.id)) <= MuseumRules.RANGE }.maxOfOrNull { MuseumRules.atmospherePct(working(it)) } ?: 0
                val c = courts.filter { gap(fps.getValue(b.id), fps.getValue(it.id)) <= COURT_RANGE }
                    .maxOfOrNull { BuildingCatalog.level(BuildingType.BACKYARD_COURT, working(it)).effect2 } ?: 0
                bonus = bonus.copy(museum = m, court = c, collection = collectionPct, event = eventPct)
            }
            b.id to bonus
        }
    }
}
