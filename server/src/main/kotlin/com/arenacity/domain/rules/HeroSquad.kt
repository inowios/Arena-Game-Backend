package com.arenacity.domain.rules

import com.arenacity.domain.model.Athlete
import com.arenacity.domain.model.ClubHero
import com.arenacity.domain.model.HeroGroup
import com.arenacity.domain.model.HeroLineup
import com.arenacity.domain.model.Rarity
import com.arenacity.domain.model.Sport
import com.arenacity.engine.model.Position
import java.time.Instant
import java.util.UUID

/**
 * The heroes in the simulated matches (the owner, 18 October 2026: the league, the cup, friendlies and challenges are
 * played by the club's heroes, as «ГРАТИ МАТЧ» is). A hero enters the match simulator as an athlete of its own: a
 * stable id per club and hero ([idOf]), its name, its role as the position, attributes from its rarity and level (the
 * strength bonus up to +10 % at level 10, [Heroes.boostPct]), always fresh. The eleven are filled with the club's
 * academy pupils (its athletes), as the hero lineup's empty places are. The match never writes these athletes back.
 */
object HeroSquad {
    /** The base attribute of a hero by rarity: above an academy pupil of the same stage, so the heroes play. */
    fun base(group: HeroGroup): Int = when (group) {
        HeroGroup.COMMON -> 70
        HeroGroup.GOOD -> 74
        HeroGroup.STAR -> 78
        HeroGroup.LEGEND -> 82
    }

    /** The athlete id a hero plays under in [clubId]'s matches (its name in the match highlights comes from [names]). */
    fun idOf(clubId: UUID, heroId: String): UUID = UUID.nameUUIDFromBytes("hero:$clubId:$heroId".toByteArray())

    /**
     * The heroes who take part: the saved lineup's field, keeper and bench when there is one (in that order), else every
     * hero the club owns. Unknown ids are skipped.
     */
    fun playing(owned: List<ClubHero>, lineup: HeroLineup?): List<ClubHero> {
        val byId = owned.associateBy { it.heroId }
        if (lineup == null) return owned.filter { HeroCatalog.of(it.heroId) != null }
        return (lineup.field + listOfNotNull(lineup.keeper) + lineup.bench).distinct().mapNotNull { byId[it] }.filter { HeroCatalog.of(it.heroId) != null }
    }

    /** [heroes] as athletes of [clubId] for the match simulator. */
    fun athletes(clubId: UUID, heroes: List<ClubHero>, now: Instant): List<Athlete> = heroes.mapNotNull { h ->
        val spec = HeroCatalog.of(h.heroId) ?: return@mapNotNull null
        val b = base(spec.group) * (100 + Heroes.boostPct(h.level)) / 100
        fun at(d: Int) = (b + d).coerceIn(1, 99)
        val pos = when (spec.role) { "GK" -> Position.GK; "DEF" -> Position.DEF; "MID" -> Position.MID; else -> Position.FWD }
        val (atk, def, phy, tec, men) = when (pos) {
            Position.GK -> listOf(at(-12), at(6), at(0), at(0), at(4))
            Position.DEF -> listOf(at(-8), at(6), at(4), at(-2), at(0))
            Position.MID -> listOf(at(0), at(-2), at(0), at(6), at(2))
            else -> listOf(at(6), at(-10), at(2), at(4), at(0))
        }
        Athlete(idOf(clubId, h.heroId), clubId, spec.name, Sport.FOOTBALL, pos, atk, def, phy, tec, men, 18,
            when (spec.group) { HeroGroup.COMMON -> Rarity.COMMON; HeroGroup.GOOD, HeroGroup.STAR -> Rarity.RARE; HeroGroup.LEGEND -> Rarity.ELITE },
            0, now, null, 80, now, now, potential = 99)
    }

    /** The names of [clubId]'s heroes under their match ids (the highlights' scorers). */
    fun names(clubId: UUID, owned: List<ClubHero>): Map<UUID, String> =
        owned.mapNotNull { h -> HeroCatalog.of(h.heroId)?.let { idOf(clubId, h.heroId) to it.name } }.toMap()
}

private operator fun <T> List<T>.component5(): T = this[4]
