package com.matchdayclub.presentation.dto

import com.matchdayclub.domain.usecase.alliance.*
import kotlinx.serialization.Serializable

@Serializable data class AllianceDto(val id: String, val name: String, val tag: String, val description: String, val emblemJson: String?, val open: Boolean, val minTrophies: Int, val members: Int)
@Serializable data class AllianceMemberDto(val club: ClubRefDto, val role: String, val trophies: Int, val helpsGiven: Int, val joinedAt: String)
@Serializable data class JoinRequestDto(val club: ClubRefDto, val trophies: Int, val message: String, val createdAt: String)
@Serializable data class HelpDto(val id: String, val club: ClubRefDto, val buildingType: String, val level: Int, val helps: Int, val maxHelps: Int, val helpedByMe: Boolean, val remainingSeconds: Long, val mine: Boolean)
@Serializable data class AllianceViewDto(val alliance: AllianceDto, val members: List<AllianceMemberDto>, val requests: List<JoinRequestDto>, val helps: List<HelpDto>, val myRole: String?, val maxMembers: Int)
@Serializable data class CreateAllianceRequest(val name: String, val tag: String, val description: String = "", val open: Boolean = true, val minTrophies: Int = 0, val emblemJson: String? = null)
@Serializable data class UpdateAllianceRequest(val description: String? = null, val open: Boolean? = null, val minTrophies: Int? = null, val emblemJson: String? = null)
@Serializable data class JoinAllianceRequest(val message: String = "")
@Serializable data class AskHelpRequest(val buildingId: String)
@Serializable data class HelpResultDto(val coins: Long, val secondsCut: Long, val club: ClubDto)
@Serializable data class TournamentRowDto(val alliance: AllianceDto, val wins: Int, val place: Int)
@Serializable data class WeekendRewardDto(val week: Int, val place: Int, val wins: Int, val gold: Int, val coins: Long)
@Serializable data class TournamentDto(val week: Int, val live: Boolean, val rows: List<TournamentRowDto>, val mine: TournamentRowDto?, val myWins: Int, val claimable: WeekendRewardDto?)
@Serializable data class LikeStateDto(val likes: Int, val likedByMe: Boolean, val alliance: AllianceDto?)
@Serializable data class TopLikedDto(val club: ClubRefDto, val likes: Int)

fun Alliance.toDto(members: Int) = AllianceDto(id.toString(), name, tag, description, emblemJson, open, minTrophies, members)
fun AllianceSummary.toDto() = alliance.toDto(members)
fun AllianceView.toDto() = AllianceViewDto(alliance.toDto(members.size),
    members.map { (m, c) -> AllianceMemberDto(c.toRef(), m.role.name, c.trophies, m.helpsGiven, m.joinedAt.toString()) },
    requests.map { (r, c) -> JoinRequestDto(c.toRef(), c.trophies, r.message, r.createdAt.toString()) },
    helps.map { h -> HelpDto(h.help.id.toString(), h.club.toRef(), h.help.buildingType, h.help.level, h.help.helps, h.help.maxHelps, h.helpedByMe, h.remainingSeconds, h.club.id == me?.clubId) },
    me?.role?.name, AllianceRules.MAX_MEMBERS)
fun TournamentRow.toDto() = TournamentRowDto(alliance.toDto(0), wins, place)
fun WeekendReward.toDto() = WeekendRewardDto(week, place, wins, gold, coins)
fun TournamentView.toDto() = TournamentDto(week, live, rows.map { it.toDto() }, mine?.toDto(), myWins, claimable?.toDto())
