package com.arenacity.presentation.dto

import com.arenacity.domain.model.Club
import com.arenacity.domain.model.People
import com.arenacity.domain.model.ZoneState
import com.arenacity.domain.rules.ClubLevels
import com.arenacity.domain.rules.MapSpot
import com.arenacity.domain.rules.Zones
import com.arenacity.domain.usecase.club.ClubSpots
import com.arenacity.domain.usecase.club.ClubState
import kotlinx.serialization.Serializable

@Serializable data class CreateClubRequest(val name: String = "", val primaryColor: String, val secondaryColor: String, val timezone: String = "UTC")
@Serializable data class UpdateClubRequest(val name: String? = null, val primaryColor: String? = null, val secondaryColor: String? = null, val emblemJson: String? = null)

@Serializable data class ClubDto(
    val id: String, val name: String, val primaryColor: String, val secondaryColor: String, val emblemJson: String?,
    val level: Int, val reputation: Int, val reputationForNextLevel: Int?, val coins: Long, val gold: Int, val timezone: String,
    val streak: Int = 0, val loginDay: Int = 0,
    val division: Int = 1, val seasonNo: Int = 1,
    val kit: String? = null, val emblemFrame: String? = null, val celebration: String? = null,
    val trophies: Int = 0, val arena: String = "YARD", val liveStreak: Int = 0,
)

/**
 * A spot of the five islands map (GET /catalog/spots, rules.MapSpots): [id] (stadium, academy, hospital, mall, lighthouse,
 * marina; android_stadium, stone_arena, beast_dome, shadow_ruins), [island] ("main" or the people's island: beast, stone,
 * android, shadow), [cost] the coins that open it (a main spot; for an island spot its island's price — it opens with the
 * island, POST /club/zones/{people}/buy), [people] the people of its island (null on the main island).
 */
@Serializable data class SpotDto(val id: String, val island: String, val cost: Long, val people: String? = null)
fun MapSpot.toDto() = SpotDto(id, island, cost, people?.name)

/**
 * GET /club. [stadium] = the stadium level: the number of open spots (1..10); [spots] = the open spot ids; [land] = the
 * islands (see [LandDto]).
 */
@Serializable data class ClubStateResponse(val club: ClubDto, val serverTime: String, val sections: List<String> = listOf("FOOTBALL"), val inboxUnread: Int = 0,
                                          val stadium: Int = 1,
                                          val spots: List<String> = listOf("stadium"),
                                          val land: LandDto? = null,
                                          /** The club's energy now (rules.Energy). */
                                          val energy: EnergyDto? = null)
/**
 * Energy (rules.Energy): [current] of [max], +1 every [regenSeconds]; [nextAt] = the next +1, [fullAt] = full again (both
 * null when full); [refillPrice] = gems to fill it up; [cardMatchCost] / [friendlyCost] = what an Arena match and a
 * friendly take (league matches are free).
 */
@Serializable data class EnergyDto(val current: Int, val max: Int, val nextAt: String?, val fullAt: String?, val regenSeconds: Long, val refillPrice: Int,
                                   val cardMatchCost: Int = com.arenacity.domain.rules.Energy.CARD_MATCH, val friendlyCost: Int = com.arenacity.domain.rules.Energy.FRIENDLY)
@Serializable data class EnergyRefillResponse(val energy: EnergyDto, val club: ClubDto)
fun com.arenacity.domain.rules.EnergyState.toDto() = EnergyDto(current, max, nextAt?.toString(), fullAt?.toString(), regenSeconds, refillGold)
/**
 * The islands: [regions] = the islands open — "main" and each people's island whose zone is OPEN (beast, stone, android,
 * shadow); [locked] = the peoples' islands not open yet; [peoples] = the peoples that have returned (their island open;
 * humans always live on the main island and are not listed; rules.Peoples). [maskId]: always null (the land mask went
 * with the city builder).
 */
@Serializable data class LandDto(val maskId: String? = null, val regions: List<String> = emptyList(), val locked: List<LockedLandDto> = emptyList(),
                                 val peoples: List<String> = emptyList())
/**
 * A people's island not open yet: its island [id] (beast, stone, android, shadow), its [people] (BEAST, STONE, ANDROID,
 * SHADOW; [zone] the same, the field older clients read), [state] LOCKED (not started) or RESTORING (paid, opening), and
 * the stadium level opening it needs.
 */
@Serializable data class LockedLandDto(val id: String, val zone: String?, val requiredStadium: Int, val state: String = "LOCKED", val people: String? = null)

fun ClubSpots.toLandDto() = LandDto(null,
    listOf(com.arenacity.domain.rules.MapSpots.MAIN) + People.entries.filter { it in peoples }.map { it.island },
    People.entries.filter { it !in peoples }.map { p -> LockedLandDto(p.island, p.name, Zones.spec(p).requiredStadium, (zones[p] ?: ZoneState.LOCKED).name, p.name) },
    com.arenacity.domain.rules.Peoples.returned(peoples).map { it.name })

fun Club.toDto() = ClubDto(id.toString(), name, primaryColor, secondaryColor, emblemJson, level, reputation, ClubLevels.reputationForNext(level), coins, gold, timezone, streak, loginDay, division, seasonNo, kit, emblemFrame, celebration, trophies, com.arenacity.domain.rules.Arenas.of(trophies).key, liveStreak)

/** GET /club's body for [st]; [inboxUnread] and [sections] are read by the route. */
fun ClubState.toResponse(sections: List<String>, inboxUnread: Int) = ClubStateResponse(club.toDto(), now.toString(), sections, inboxUnread, stadium, spots.open, spots.toLandDto(),
    com.arenacity.domain.rules.Energy.state(club, now).toDto())
