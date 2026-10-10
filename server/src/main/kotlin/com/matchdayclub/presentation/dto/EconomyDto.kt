package com.matchdayclub.presentation.dto

import com.matchdayclub.domain.model.Club
import com.matchdayclub.domain.model.DailyTask
import com.matchdayclub.domain.rules.Achievements
import com.matchdayclub.domain.rules.Cosmetic
import com.matchdayclub.domain.rules.Economy
import com.matchdayclub.domain.rules.Reward
import com.matchdayclub.domain.rules.TaskKind
import com.matchdayclub.domain.usecase.economy.AchievementState
import com.matchdayclub.domain.usecase.economy.Inventory
import com.matchdayclub.domain.usecase.economy.PassView
import kotlinx.serialization.Serializable

@Serializable data class RewardDto(val coins: Long, val gold: Int)
@Serializable data class DailyClaimResponse(val day: Int, val reward: RewardDto, val streak: Int, val club: ClubDto, val calendar: List<RewardDto>)
@Serializable data class TaskDto(val task: String, val title: String, val progress: Int, val target: Int, val xp: Int, val claimed: Boolean)
@Serializable data class PassLevelDto(
    val level: Int, val free: RewardDto, val premium: RewardDto, val reached: Boolean, val claimedFree: Boolean, val claimedPremium: Boolean,
    val freeItem: String? = null, val premiumItem: String? = null, val elite: Boolean = false,
)
@Serializable data class ThemeDto(val id: String, val accent: String, val accent2: String, val item: String, val itemLevel: Int)
@Serializable data class PassResponse(val season: Int, val xp: Int, val level: Int, val xpPerLevel: Int, val premium: Boolean, val tasks: List<TaskDto>, val levels: List<PassLevelDto>, val theme: ThemeDto? = null)
@Serializable data class ClaimLevelsRequest(val eliteSport: String? = null)
@Serializable data class ClaimLevelsResponse(val reward: RewardDto, val pass: PassResponse, val club: ClubDto, val items: List<String> = emptyList(), val elite: AthleteDto? = null)
@Serializable data class VerifyPurchaseRequest(val store: String, val productId: String, val token: String)
@Serializable data class PurchaseResponse(val status: String, val gold: Int, val coins: Long, val club: ClubDto)
@Serializable data class AdRewardRequest(val placement: String, val buildingId: String? = null)
@Serializable data class PushTokenRequest(val token: String, val platform: String = "android")
@Serializable data class ProductDto(val id: String, val gold: Int, val coins: Long, val premiumPass: Boolean)
@Serializable data class CoinPackDto(val id: String, val gold: Int, val coins: Long)
@Serializable data class CosmeticDto(val id: String, val kind: String, val source: String, val gold: Int, val rarity: String, val unlock: String? = null)
@Serializable data class EquippedDto(val kit: String?, val frame: String?, val celebration: String?)
@Serializable data class InventoryResponse(val owned: List<String>, val equipped: EquippedDto, val club: ClubDto)
@Serializable data class EquipRequest(val kind: String, val itemId: String? = null)
@Serializable data class VariantRequest(val itemId: String)
@Serializable data class AchievementDto(
    val id: String, val metric: String, val value: Long, val tiers: List<Int>, val gold: List<Int>, val items: Map<Int, String>,
    val claimedTier: Int, val reachedTier: Int,
)
@Serializable data class AchievementClaimResponse(val gold: Int, val items: List<String>, val achievements: List<AchievementDto>, val club: ClubDto)
@Serializable data class SpeedUpTrainingResponse(val athlete: AthleteDto, val club: ClubDto)

fun Reward.toDto() = RewardDto(coins, gold)
fun DailyTask.toDto() = TaskKind.valueOf(task).let { k -> TaskDto(task, k.title, progress, k.target, k.xp, claimed) }
fun PassView.toDto() = PassResponse(seasonNumber, pass.xp, level, Economy.XP_PER_LEVEL, pass.premium, tasks.map { it.toDto() },
    (1..Economy.PASS_LEVELS).map { l ->
        PassLevelDto(l, Economy.freeReward(l).toDto(), Economy.premiumReward(l).toDto(), l <= level, l <= pass.claimedFree, l <= pass.claimedPremium,
            Economy.passItem(l, false)?.id, Economy.passItem(l, true)?.id, l == Economy.ELITE_LEVEL)
    }, ThemeDto(theme.id, theme.accent, theme.accent2, theme.item, com.matchdayclub.domain.rules.SeasonThemes.ITEM_LEVEL))

fun Cosmetic.toDto() = CosmeticDto(id, kind.name, source.name, gold, rarity.name, unlock)
fun Club.equipped() = EquippedDto(kit, emblemFrame, celebration)
fun Inventory.toDto() = InventoryResponse(owned.sorted(), club.equipped(), club.toDto())
fun AchievementState.toDto() = AchievementDto(achievement.id, achievement.metric.name, value, achievement.tiers, achievement.gold,
    (1..achievement.tiers.size).mapNotNull { t -> Achievements.item(achievement.id, t)?.let { t to it.id } }.toMap(), claimedTier, reachedTier)
