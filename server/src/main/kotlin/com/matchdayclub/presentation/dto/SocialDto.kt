package com.matchdayclub.presentation.dto

import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.usecase.social.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class ChatMessageDto(val id: String, val kind: String, val text: String, val createdAt: String, val club: ClubRefDto? = null)
@Serializable data class PostMessageRequest(val text: String)
@Serializable data class ReportRequest(val reason: String = "abuse")
@Serializable data class PrivateSeasonDto(val seasonNumber: Int, val winnerClubId: String?, val table: List<FrozenRow>, val endedAt: String)
@Serializable data class HeadToHeadDto(val me: ClubRefDto, val other: ClubRefDto, val won: Int, val drawn: Int, val lost: Int, val fixtures: List<FixtureDto>)
@Serializable data class GroundBuildingDto(val id: String, val type: String, val level: Int, val x: Int, val y: Int, val rotation: Int, val state: String)
@Serializable data class GroundsDto(val club: ClubRefDto, val reputation: Int, val division: Int, val gridSize: Int, val sections: List<String>, val buildings: List<GroundBuildingDto>)
@Serializable data class ChallengeDto(val code: String, val status: String, val expiresAt: String, val challenger: ClubRefDto?, val acceptedBy: ClubRefDto? = null, val fixtureId: String? = null, val link: String, val appLink: String)
@Serializable data class LeagueInviteDto(val league: LeagueDto, val owner: ClubRefDto?, val alreadyMember: Boolean)
@Serializable data class ClientConfigDto(val publicBaseUrl: String, val scheme: String, val minVersion: String, val playStoreUrl: String, val appStoreUrl: String, val leagueLink: String, val challengeLink: String)

fun ChatLine.toDto() = ChatMessageDto(message.id.toString(), message.kind.name, message.text, message.createdAt.toString(), club?.toRef())
fun PrivateLeagueSeason.toDto() = PrivateSeasonDto(seasonNumber, winnerClubId?.toString(), runCatching { Json.decodeFromString<List<FrozenRow>>(tableJson) }.getOrDefault(emptyList()), endedAt.toString())
fun HeadToHead.toDto(clubs: Map<java.util.UUID, Club>) = HeadToHeadDto(me.toRef(), other.toRef(), won, drawn, lost, fixtures.map { it.toDto(clubs) })
fun Grounds.toDto() = GroundsDto(club.toRef(), club.reputation, club.division, gridSize, sections.map { it.name }, buildings.map { GroundBuildingDto(it.id.toString(), it.type.name, it.level, it.gridX, it.gridY, it.rotation, it.state.name) })
fun ChallengeView.toDto(baseUrl: String, scheme: String) = ChallengeDto(challenge.code, challenge.status.name, challenge.expiresAt.toString(), challenger.toRef(), acceptedBy?.toRef(), challenge.fixtureId?.toString(), "$baseUrl/c/${challenge.code}", "$scheme://c/${challenge.code}")
fun Challenge.toDto(challenger: Club?, baseUrl: String, scheme: String) = ChallengeDto(code, status.name, expiresAt.toString(), challenger?.toRef(), null, fixtureId?.toString(), "$baseUrl/c/$code", "$scheme://c/$code")
