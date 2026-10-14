package com.arenacity.domain.rules

import com.arenacity.domain.model.Sport
import com.arenacity.domain.model.ZoneSport

/**
 * What a stadium zone costs and needs (CITY-BUILD-PLAN §6). An OPEN zone opens its side island, where its arena is built
 * (BuildingSpec.island), so an island opens at the stadium its arena does (the sport town, 14 October: basketball 3,
 * volleyball 5, tennis 7, padel 9) — an island opened earlier would stay empty and its section without a squad. Hockey
 * has no island on the map yet: it is not sold (ZoneUseCases.buy).
 */
data class ZoneSpec(val sport: ZoneSport, val priceCoins: Long, val requiredStadium: Int, val restoreSeconds: Long, val engineSport: Sport?) {
    /** The sport has no match engine yet: the arena opens, the section comes later. */
    val soon get() = engineSport == null
}

object Zones {
    private const val H = 3600L
    val specs: Map<ZoneSport, ZoneSpec> = listOf(
        ZoneSpec(ZoneSport.BASKETBALL, 8_000, 3, 2 * H, Sport.BASKETBALL),
        ZoneSpec(ZoneSport.VOLLEYBALL, 50_000, 5, 12 * H, null),
        ZoneSpec(ZoneSport.TENNIS, 40_000, 7, 6 * H, Sport.TENNIS),
        ZoneSpec(ZoneSport.PADEL, 120_000, 9, 12 * H, null),
        ZoneSpec(ZoneSport.HOCKEY, 80_000, 7, 24 * H, null),
    ).associateBy { it.sport }

    fun spec(sport: ZoneSport) = specs.getValue(sport)
    /** The island arena standing on [sport]'s side island (null: the sport has no island yet). */
    fun arenaOf(sport: ZoneSport): BuildingSpec? = BuildingCatalog.specs.values.firstOrNull { it.island == sport }
    /**
     * The stadium a club needs to open [sport]'s island: the zone's own, which is its arena's; a club that already owns
     * the arena (an old court the sport town made into it, V36) from the stadium the arena's first level needs, so the
     * arena it had is not kept parked off its island for stadium levels it once could open the island at.
     */
    fun requiredStadium(sport: ZoneSport, owned: Collection<BuildingType>): Int {
        val z = spec(sport).requiredStadium
        val arena = arenaOf(sport) ?: return z
        return if (arena.type in owned) minOf(z, arena.levels.first().requiredStadium) else z
    }
    /** The zone whose open arena stands for the venue of an engine sport. */
    fun zoneOf(sport: Sport): ZoneSport? = specs.values.firstOrNull { it.engineSport == sport }?.sport
    /** The venue level an open arena counts as for the section's attribute cap. */
    const val ARENA_VENUE_LEVEL = 3
}
