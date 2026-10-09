package com.matchdayclub.presentation.dto

import com.matchdayclub.domain.model.Club
import com.matchdayclub.domain.model.Fixture
import com.matchdayclub.domain.model.League
import com.matchdayclub.domain.rules.StandingRow
import com.matchdayclub.domain.usecase.league.*
import com.matchdayclub.engine.model.MatchResult
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class ClubRefDto(val id: String, val name: String, val primaryColor: String, val secondaryColor: String, val level: Int, val isBot: Boolean, val emblemJson: String? = null)
@Serializable data class LeagueDto(val id: String, val kind: String, val name: String, val code: String?, val division: Int, val seasonNumber: Int, val seasonStart: String, val currentRound: Int, val totalRounds: Int, val matchdayHour: Int, val timezone: String, val nextRoundAt: String?, val memberCount: Int)
@Serializable data class StandingRowDto(val position: Int, val club: ClubRefDto, val played: Int, val won: Int, val drawn: Int, val lost: Int, val gf: Int, val ga: Int, val gd: Int, val points: Int)
@Serializable data class LeagueTableResponse(val league: LeagueDto, val table: List<StandingRowDto>)
@Serializable data class MyLeaguesResponse(val public: LeagueTableResponse?, val private: List<LeagueTableResponse>)
@Serializable data class FixtureDto(val id: String, val leagueId: String?, val kind: String, val seasonNumber: Int, val round: Int, val scheduledAt: String, val status: String, val home: ClubRefDto, val away: ClubRefDto, val homeScore: Int?, val awayScore: Int?, val playedAt: String?)
@Serializable data class MatchResponse(val fixture: FixtureDto, val result: MatchResult?, val athletes: Map<String, String> = emptyMap())
@Serializable data class ClubFixturesResponse(val next: FixtureDto?, val recent: List<FixtureDto>)
@Serializable data class PublicClubDto(val club: ClubRefDto, val reputation: Int, val squadOverall: Int, val pitchLevel: Int)
@Serializable data class CreatePrivateLeagueRequest(val name: String)
@Serializable data class JoinPrivateLeagueRequest(val code: String)
@Serializable data class PlayFriendlyRequest(val opponentClubId: String? = null)

private val unknown = { id: UUID -> ClubRefDto(id.toString(), "Unknown", "#777777", "#FFFFFF", 1, true, null) }
fun Club.toRef() = ClubRefDto(id.toString(), name, primaryColor, secondaryColor, level, isBot, emblemJson)
fun Map<UUID, Club>.ref(id: UUID) = this[id]?.toRef() ?: unknown(id)

fun LeagueView.toDto() = LeagueTableResponse(
    LeagueDto(league.id.toString(), league.kind.name, league.name, league.code, league.division, league.seasonNumber, league.seasonStart.toString(), league.currentRound, 28, league.matchdayHour, league.timezone, nextRoundAt?.toString(), memberCount),
    table.mapIndexed { i, r -> StandingRowDto(i + 1, clubs.ref(r.clubId), r.played, r.won, r.drawn, r.lost, r.gf, r.ga, r.gd, r.points) },
)
fun League.toDto(memberCount: Int) = LeagueDto(id.toString(), kind.name, name, code, division, seasonNumber, seasonStart.toString(), currentRound, 28, matchdayHour, timezone, null, memberCount)
fun Fixture.toDto(clubs: Map<UUID, Club>) = FixtureDto(id.toString(), leagueId?.toString(), kind.name, seasonNumber, round, scheduledAt.toString(), status.name, clubs.ref(homeClubId), clubs.ref(awayClubId), homeScore, awayScore, playedAt?.toString())
fun MatchView.toDto() = MatchResponse(fixture.toDto(clubs), result, athleteNames)
fun PublicClub.toDto() = PublicClubDto(club.toRef(), club.reputation, squadOverall, pitchLevel)
