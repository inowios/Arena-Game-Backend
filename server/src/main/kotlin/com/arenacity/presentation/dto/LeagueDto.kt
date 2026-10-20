package com.arenacity.presentation.dto

import com.arenacity.domain.model.Club
import com.arenacity.domain.model.Fixture
import com.arenacity.domain.model.League
import com.arenacity.domain.model.MatchHero
import com.arenacity.domain.model.MatchLine
import com.arenacity.domain.model.MatchdayResult
import com.arenacity.domain.rules.HeroCatalog
import com.arenacity.domain.rules.StandingRow
import com.arenacity.domain.usecase.league.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class ClubRefDto(val id: String, val name: String, val primaryColor: String, val secondaryColor: String, val level: Int, val isBot: Boolean, val emblemJson: String? = null, val frame: String? = null, val kit: String? = null)
@Serializable data class LeagueDto(val id: String, val kind: String, val name: String, val code: String?, val division: Int, val seasonNumber: Int, val seasonStart: String, val currentRound: Int, val totalRounds: Int, val matchdayHour: Int, val timezone: String, val nextRoundAt: String?, val memberCount: Int, val createdBy: String? = null, val status: String = "ACTIVE")
@Serializable data class StandingRowDto(val position: Int, val club: ClubRefDto, val played: Int, val won: Int, val drawn: Int, val lost: Int, val gf: Int, val ga: Int, val gd: Int, val points: Int)
@Serializable data class LeagueTableResponse(val league: LeagueDto, val table: List<StandingRowDto>)
@Serializable data class MyLeaguesResponse(val public: LeagueTableResponse?, val private: List<LeagueTableResponse>)
/**
 * A fixture: the Arena match of two hero triples. [homeScore]/[awayScore] = 1:0 to the winner, [homePoints]/[awayPoints] = 3 / 0
 * (null until played); [homeWon] once played.
 */
@Serializable data class FixtureDto(
    val id: String, val leagueId: String?, val kind: String, val seasonNumber: Int, val round: Int, val scheduledAt: String, val status: String,
    val home: ClubRefDto, val away: ClubRefDto, val homeScore: Int?, val awayScore: Int?, val playedAt: String?,
    val homePoints: Int? = null, val awayPoints: Int? = null, val homeWon: Boolean? = null,
)
/** A hero of a played fixture: the catalog [id] and [name], its [level] then, the [stamina] it ended on (0 = taken out). */
@Serializable data class MatchHeroDto(val id: String, val name: String, val level: Int, val stamina: Int)
/** A line of the short match log (see MatchLine): [kind] cast | tick | out | end, [side] HOME | AWAY, the [hero] and [target] ids, the [skill] name and [skillId], the [damage] dealt. */
@Serializable data class MatchLineDto(val turn: Int, val side: String, val kind: String, val hero: String? = null, val skill: String? = null, val skillId: String? = null, val target: String? = null, val damage: Int = 0, val text: String? = null)
@Serializable data class MatchHeroesDto(val home: List<MatchHeroDto>, val away: List<MatchHeroDto>)
/**
 * GET /matches/{id} (the league screen's result card; friendlies and challenges answer the same): the fixture's [id] and
 * [fixture], the two clubs, who won ([homeWon]; null while scheduled), the [turns] played, [first] = the side that moved
 * first, the two triples as they ended ([heroes]) and the short log ([events]). An old fixture (played by the athlete
 * simulation before 20 October 2026) has its result but no heroes and no log.
 */
@Serializable data class MatchResponse(val id: String, val fixture: FixtureDto, val home: ClubRefDto, val away: ClubRefDto, val homeWon: Boolean? = null, val turns: Int = 0,
                                       val first: String? = null, val heroes: MatchHeroesDto = MatchHeroesDto(emptyList(), emptyList()), val events: List<MatchLineDto> = emptyList())
@Serializable data class ClubFixturesResponse(val next: FixtureDto?, val recent: List<FixtureDto>)
@Serializable data class ClubSeasonDto(
    val seasonNo: Int, val division: Int, val position: Int, val played: Int, val won: Int, val drawn: Int, val lost: Int, val gf: Int, val ga: Int, val points: Int,
    val movement: String, val newDivision: Int, val prizeCoins: Long, val prizeGold: Int, val prizeReputation: Int, val finishedAt: String, val leagueId: String,
)
@Serializable data class TrophyDto(val kind: String, val division: Int, val seasonNo: Int, val createdAt: String)
@Serializable data class ClubHistoryResponse(val seasons: List<ClubSeasonDto>, val trophies: List<TrophyDto>)
@Serializable data class CreatePrivateLeagueRequest(val name: String)
@Serializable data class JoinPrivateLeagueRequest(val code: String)
@Serializable data class PlayFriendlyRequest(val opponentClubId: String? = null)

private val unknown = { id: UUID -> ClubRefDto(id.toString(), "Unknown", "#777777", "#FFFFFF", 1, true, null) }
fun Club.toRef() = ClubRefDto(id.toString(), name, primaryColor, secondaryColor, level, isBot, emblemJson, emblemFrame, kit)
fun Map<UUID, Club>.ref(id: UUID) = this[id]?.toRef() ?: unknown(id)

fun LeagueView.toDto() = LeagueTableResponse(
    LeagueDto(league.id.toString(), league.kind.name, league.name, league.code, league.division, league.seasonNumber, league.seasonStart.toString(), league.currentRound, 28, league.matchdayHour, league.timezone, nextRoundAt?.toString(), memberCount, league.createdBy?.toString(), league.status.name),
    table.mapIndexed { i, r -> StandingRowDto(i + 1, clubs.ref(r.clubId), r.played, r.won, r.drawn, r.lost, r.gf, r.ga, r.gd, r.points) },
)
fun League.toDto(memberCount: Int) = LeagueDto(id.toString(), kind.name, name, code, division, seasonNumber, seasonStart.toString(), currentRound, 28, matchdayHour, timezone, null, memberCount, createdBy?.toString(), status.name)
fun Fixture.toDto(clubs: Map<UUID, Club>): FixtureDto = FixtureDto(
    id.toString(), leagueId?.toString(), kind.name, seasonNumber, round, scheduledAt.toString(), status.name, clubs.ref(homeClubId), clubs.ref(awayClubId), homeScore, awayScore, playedAt?.toString(),
    homePoints, awayPoints, if (homePoints != null && awayPoints != null) homePoints > awayPoints else null,
)
fun MatchHero.toDto() = MatchHeroDto(id, HeroCatalog.of(id)?.name ?: id, level, stamina)
fun MatchLine.toDto() = MatchLineDto(turn, side, kind, hero, skill, skillId, target, damage, text)
fun MatchView.toDto(): MatchResponse {
    val r = result
    return MatchResponse(fixture.id.toString(), fixture.toDto(clubs), clubs.ref(fixture.homeClubId), clubs.ref(fixture.awayClubId), r?.homeWon, r?.turns ?: 0, r?.first?.takeIf { r.v >= 3 },
        MatchHeroesDto(r?.home?.map { it.toDto() } ?: emptyList(), r?.away?.map { it.toDto() } ?: emptyList()), r?.events?.map { it.toDto() } ?: emptyList())
}
fun com.arenacity.domain.model.ClubSeason.toDto() = ClubSeasonDto(seasonNo, division, position, played, won, drawn, lost, gf, ga, points, movement.name, newDivision, prizeCoins, prizeGold, prizeReputation, finishedAt.toString(), leagueId.toString())
fun com.arenacity.domain.model.Trophy.toDto() = TrophyDto(kind.name, division, seasonNo, createdAt.toString())
fun ClubHistory.toDto() = ClubHistoryResponse(seasons.map { it.toDto() }, trophies.map { it.toDto() })
