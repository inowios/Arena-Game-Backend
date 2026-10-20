package com.arenacity.presentation.dto

import com.arenacity.domain.model.Club
import com.arenacity.domain.model.Fixture
import com.arenacity.domain.model.League
import com.arenacity.domain.model.MatchdayResult
import com.arenacity.domain.rules.StandingRow
import com.arenacity.domain.usecase.league.*
import com.arenacity.engine.model.MatchResult
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class ClubRefDto(val id: String, val name: String, val primaryColor: String, val secondaryColor: String, val level: Int, val isBot: Boolean, val emblemJson: String? = null, val frame: String? = null, val kit: String? = null)
@Serializable data class LeagueDto(val id: String, val kind: String, val name: String, val code: String?, val division: Int, val seasonNumber: Int, val seasonStart: String, val currentRound: Int, val totalRounds: Int, val matchdayHour: Int, val timezone: String, val nextRoundAt: String?, val memberCount: Int, val createdBy: String? = null, val status: String = "ACTIVE")
@Serializable data class StandingRowDto(val position: Int, val club: ClubRefDto, val played: Int, val won: Int, val drawn: Int, val lost: Int, val gf: Int, val ga: Int, val gd: Int, val points: Int)
@Serializable data class LeagueTableResponse(val league: LeagueDto, val table: List<StandingRowDto>)
@Serializable data class MyLeaguesResponse(val public: LeagueTableResponse?, val private: List<LeagueTableResponse>)
@Serializable data class SectionScoreDto(val sport: String, val home: Int, val away: Int, val walkover: String? = null)
@Serializable data class FixtureDto(
    val id: String, val leagueId: String?, val kind: String, val seasonNumber: Int, val round: Int, val scheduledAt: String, val status: String,
    val home: ClubRefDto, val away: ClubRefDto, val homeScore: Int?, val awayScore: Int?, val playedAt: String?,
    val homePoints: Int? = null, val awayPoints: Int? = null, val sections: List<SectionScoreDto> = emptyList(),
    /** Cup ties level after every section: the penalty shootout. */
    val shootout: ShootoutDto? = null,
)
@Serializable data class SectionDto(val sport: String, val homeScore: Int, val awayScore: Int, val homePoints: Int, val awayPoints: Int, val walkover: String? = null, val result: MatchResult? = null)
/** [result] is the football match (kept for single-sport clients); [sections] has every sport played that matchday. */
@Serializable data class MatchResponse(val fixture: FixtureDto, val result: MatchResult?, val athletes: Map<String, String> = emptyMap(), val sections: List<SectionDto> = emptyList())
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
fun Fixture.toDto(clubs: Map<UUID, Club>): FixtureDto {
    val parsed = resultJson?.let { runCatching { MatchdayResult.parse(it) }.getOrNull() }
    return FixtureDto(
        id.toString(), leagueId?.toString(), kind.name, seasonNumber, round, scheduledAt.toString(), status.name, clubs.ref(homeClubId), clubs.ref(awayClubId), homeScore, awayScore, playedAt?.toString(),
        homePoints, awayPoints, parsed?.sections?.map { s -> SectionScoreDto(s.sport, s.homeScore, s.awayScore, s.walkover) } ?: emptyList(),
        parsed?.shootout?.let { ShootoutDto(it.home, it.away) },
    )
}
fun MatchView.toDto() = MatchResponse(fixture.toDto(clubs), result?.primary, athleteNames, result?.sections?.map { SectionDto(it.sport, it.homeScore, it.awayScore, it.homePoints, it.awayPoints, it.walkover, it.result) } ?: emptyList())
fun com.arenacity.domain.model.ClubSeason.toDto() = ClubSeasonDto(seasonNo, division, position, played, won, drawn, lost, gf, ga, points, movement.name, newDivision, prizeCoins, prizeGold, prizeReputation, finishedAt.toString(), leagueId.toString())
fun com.arenacity.domain.model.Trophy.toDto() = TrophyDto(kind.name, division, seasonNo, createdAt.toString())
fun ClubHistory.toDto() = ClubHistoryResponse(seasons.map { it.toDto() }, trophies.map { it.toDto() })
