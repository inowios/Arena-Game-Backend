package com.matchdayclub.presentation.dto

import com.matchdayclub.domain.model.DailyTask
import com.matchdayclub.domain.rules.Economy
import com.matchdayclub.domain.rules.Reward
import com.matchdayclub.domain.rules.TaskKind
import com.matchdayclub.domain.usecase.economy.PassView
import kotlinx.serialization.Serializable

@Serializable data class RewardDto(val coins: Long, val gold: Int)
@Serializable data class DailyClaimResponse(val day: Int, val reward: RewardDto, val streak: Int, val club: ClubDto, val calendar: List<RewardDto>)
@Serializable data class TaskDto(val task: String, val title: String, val progress: Int, val target: Int, val xp: Int, val claimed: Boolean)
@Serializable data class PassLevelDto(val level: Int, val free: RewardDto, val premium: RewardDto, val reached: Boolean, val claimedFree: Boolean, val claimedPremium: Boolean)
@Serializable data class PassResponse(val season: Int, val xp: Int, val level: Int, val xpPerLevel: Int, val premium: Boolean, val tasks: List<TaskDto>, val levels: List<PassLevelDto>)
@Serializable data class ClaimLevelsResponse(val reward: RewardDto, val pass: PassResponse, val club: ClubDto)
@Serializable data class VerifyPurchaseRequest(val store: String, val productId: String, val token: String)
@Serializable data class PurchaseResponse(val status: String, val gold: Int, val coins: Long, val club: ClubDto)
@Serializable data class AdRewardRequest(val placement: String, val buildingId: String? = null)
@Serializable data class PushTokenRequest(val token: String, val platform: String = "android")
@Serializable data class ProductDto(val id: String, val gold: Int, val coins: Long, val premiumPass: Boolean)

fun Reward.toDto() = RewardDto(coins, gold)
fun DailyTask.toDto() = TaskKind.valueOf(task).let { k -> TaskDto(task, k.title, progress, k.target, k.xp, claimed) }
fun PassView.toDto() = PassResponse(seasonNumber, pass.xp, level, Economy.XP_PER_LEVEL, pass.premium, tasks.map { it.toDto() },
    (1..Economy.PASS_LEVELS).map { l -> PassLevelDto(l, Economy.freeReward(l).toDto(), Economy.premiumReward(l).toDto(), l <= level, l <= pass.claimedFree, l <= pass.claimedPremium) })
