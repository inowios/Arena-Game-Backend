package com.arenacity.domain.rules

import com.arenacity.domain.model.Building
import java.util.UUID

/**
 * Atmosphere (like land value in SimCity): where things stand matters. An income building earns more with decor
 * around it, next to the stadium (the café, the club shop and the ticket office live off the crowd) and with a car
 * park nearby; a tidy fence line around it adds a little more. Distances are gaps between footprints in cells (0 = touching). Total bonus capped at [CAP] %.
 */
object Atmosphere {
    const val CAP = 48
    const val DECOR_PCT = 3; const val DECOR_RANGE = 2; const val DECOR_CAP = 24
    const val STADIUM_PCT = 10; const val STADIUM_RANGE = 3
    const val PARKING_PCT = 5; const val PARKING_RANGE = 3
    const val FENCE_PCT = 1; const val FENCE_RANGE = 1; const val FENCE_CAP = 8
    private val incomeTypes = setOf(BuildingType.TICKET_OFFICE, BuildingType.CLUB_SHOP, BuildingType.CAFE, BuildingType.PARKING)
    private val crowdTypes = setOf(BuildingType.TICKET_OFFICE, BuildingType.CLUB_SHOP, BuildingType.CAFE)

    /** Cells between two footprints along the worse axis (0 when they touch or overlap). */
    fun gap(a: Footprint, b: Footprint): Int {
        val dx = maxOf(0, maxOf(a.x - (b.x + b.w), b.x - (a.x + a.w)))
        val dy = maxOf(0, maxOf(a.y - (b.y + b.h), b.y - (a.y + a.h)))
        return maxOf(dx, dy)
    }

    /** [museum]: visitors of a trophy museum nearby; [collection]: complete cosmetic sets (club-wide, above the cap). */
    /** [event]: a running live event's extra income % (club-wide, above the cap like collections). */
    data class Bonus(val decor: Int = 0, val stadium: Int = 0, val parking: Int = 0, val fence: Int = 0, val museum: Int = 0, val collection: Int = 0, val event: Int = 0) {
        val total get() = minOf(CAP, decor + stadium + parking + fence + museum) + collection + event
    }

    /** The bonus a [type] building at [fp] would get among [others] (its own row excluded by the caller). */
    fun bonusAt(type: BuildingType, fp: Footprint, others: List<Pair<BuildingType, Footprint>>): Bonus {
        if (type !in incomeTypes) return Bonus()
        val decor = minOf(DECOR_CAP, others.count { it.first == BuildingType.DECOR && gap(fp, it.second) <= DECOR_RANGE } * DECOR_PCT)
        val stadium = if (type in crowdTypes && others.any { it.first == BuildingType.PITCH && gap(fp, it.second) <= STADIUM_RANGE }) STADIUM_PCT else 0
        val parking = if (type in crowdTypes && others.any { it.first == BuildingType.PARKING && gap(fp, it.second) <= PARKING_RANGE }) PARKING_PCT else 0
        val fence = minOf(FENCE_CAP, others.count { it.first == BuildingType.FENCE && gap(fp, it.second) <= FENCE_RANGE } * FENCE_PCT)
        return Bonus(decor, stadium, parking, fence)
    }

    /** Bonus of every building of a club (only income buildings get one); [collectionPct] from complete sets. */
    fun of(buildings: List<Building>, collectionPct: Int = 0, eventPct: Int = 0): Map<UUID, Bonus> {
        val fps = buildings.associate { it.id to footprint(it.type, it.gridX, it.gridY, it.rotation) }
        val museums = buildings.filter { it.type == BuildingType.MUSEUM && it.level > 0 }
        return buildings.associate { b ->
            var bonus = bonusAt(b.type, fps.getValue(b.id), buildings.filter { it.id != b.id }.map { it.type to fps.getValue(it.id) })
            if (b.type in incomeTypes) {
                // a museum under construction keeps the level it had
                val m = museums.filter { gap(fps.getValue(b.id), fps.getValue(it.id)) <= MuseumRules.RANGE }
                    .maxOfOrNull { MuseumRules.atmospherePct(if (it.state == com.arenacity.domain.model.BuildingState.UPGRADING) it.level - 1 else it.level) } ?: 0
                bonus = bonus.copy(museum = m, collection = collectionPct, event = eventPct)
            }
            b.id to bonus
        }
    }
}
