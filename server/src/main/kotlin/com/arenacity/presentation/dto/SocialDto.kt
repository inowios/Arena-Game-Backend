package com.arenacity.presentation.dto

import com.arenacity.domain.model.*
import com.arenacity.domain.usecase.social.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class ChatMessageDto(val id: String, val kind: String, val text: String, val createdAt: String, val club: ClubRefDto? = null)
@Serializable data class PostMessageRequest(val text: String)
@Serializable data class ReportRequest(val reason: String = "abuse")
@Serializable data class PrivateSeasonDto(val seasonNumber: Int, val winnerClubId: String?, val table: List<FrozenRow>, val endedAt: String)
@Serializable data class HeadToHeadDto(val me: ClubRefDto, val other: ClubRefDto, val won: Int, val drawn: Int, val lost: Int, val fixtures: List<FixtureDto>)
/**
 * A visited club (GET /clubs/{id}/grounds): [stadium] its stadium level (open spots), [spots] the open spot ids, [land] its
 * islands (as GET /club's).
 */
@Serializable data class GroundsDto(val club: ClubRefDto, val reputation: Int, val division: Int, val sections: List<String>,
                                    val stadium: Int = 1, val land: LandDto? = null, val spots: List<String> = listOf("stadium"))
@Serializable data class FriendDto(val club: ClubRefDto, val stadium: Int, val showcase: Boolean, val level: Int, val reputation: Int)
@Serializable data class ChallengeDto(val code: String, val status: String, val expiresAt: String, val challenger: ClubRefDto?, val acceptedBy: ClubRefDto? = null, val fixtureId: String? = null, val link: String, val appLink: String)
@Serializable data class LeagueInviteDto(val league: LeagueDto, val owner: ClubRefDto?, val alreadyMember: Boolean)
@Serializable data class ClientConfigDto(val publicBaseUrl: String, val scheme: String, val minVersion: String, val playStoreUrl: String, val appStoreUrl: String, val leagueLink: String, val challengeLink: String)

fun ChatLine.toDto() = ChatMessageDto(message.id.toString(), message.kind.name, message.text, message.createdAt.toString(), club?.toRef())
fun PrivateLeagueSeason.toDto() = PrivateSeasonDto(seasonNumber, winnerClubId?.toString(), runCatching { Json.decodeFromString<List<FrozenRow>>(tableJson) }.getOrDefault(emptyList()), endedAt.toString())
fun HeadToHead.toDto(clubs: Map<java.util.UUID, Club>) = HeadToHeadDto(me.toRef(), other.toRef(), won, drawn, lost, fixtures.map { it.toDto(clubs) })
fun Grounds.toDto() = GroundsDto(club.toRef(), club.reputation, club.division, sections.map { it.name }, spots.stadium, spots.toLandDto(), spots.open)
fun com.arenacity.domain.usecase.social.Friend.toDto() = FriendDto(club.toRef(), stadium, showcase, club.level, club.reputation)
fun ChallengeView.toDto(baseUrl: String, scheme: String) = ChallengeDto(challenge.code, challenge.status.name, challenge.expiresAt.toString(), challenger.toRef(), acceptedBy?.toRef(), challenge.fixtureId?.toString(), "$baseUrl/c/${challenge.code}", "$scheme://c/${challenge.code}")
fun Challenge.toDto(challenger: Club?, baseUrl: String, scheme: String) = ChallengeDto(code, status.name, expiresAt.toString(), challenger?.toRef(), null, fixtureId?.toString(), "$baseUrl/c/$code", "$scheme://c/$code")
