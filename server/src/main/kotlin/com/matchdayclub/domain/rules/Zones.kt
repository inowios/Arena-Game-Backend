package com.matchdayclub.domain.rules

import com.matchdayclub.domain.model.Sport
import com.matchdayclub.domain.model.ZoneSport

/** What a stadium zone costs and needs (CITY-BUILD-PLAN §6: a zone opens per chapter from stadium 3 to 7). */
data class ZoneSpec(val sport: ZoneSport, val priceCoins: Long, val requiredStadium: Int, val restoreSeconds: Long, val engineSport: Sport?) {
    /** The sport has no match engine yet: the arena opens, the section comes later. */
    val soon get() = engineSport == null
}

object Zones {
    private const val H = 3600L
    val specs: Map<ZoneSport, ZoneSpec> = listOf(
        ZoneSpec(ZoneSport.BASKETBALL, 8_000, 3, 2 * H, Sport.BASKETBALL),
        ZoneSpec(ZoneSport.TENNIS, 15_000, 4, 4 * H, Sport.TENNIS),
        ZoneSpec(ZoneSport.PADEL, 30_000, 5, 8 * H, null),
        ZoneSpec(ZoneSport.VOLLEYBALL, 50_000, 6, 12 * H, null),
        ZoneSpec(ZoneSport.HOCKEY, 80_000, 7, 24 * H, null),
    ).associateBy { it.sport }

    fun spec(sport: ZoneSport) = specs.getValue(sport)
    /** The zone whose open arena stands for the venue of an engine sport. */
    fun zoneOf(sport: Sport): ZoneSport? = specs.values.firstOrNull { it.engineSport == sport }?.sport
    /** The venue level an open arena counts as for the section's attribute cap. */
    const val ARENA_VENUE_LEVEL = 3
}
