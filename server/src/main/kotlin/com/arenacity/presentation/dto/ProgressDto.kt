package com.arenacity.presentation.dto

import com.arenacity.domain.usecase.progress.*
import kotlinx.serialization.Serializable

@Serializable data class RoadRewardDto(val coins: Long, val gold: Int, val item: String?)
@Serializable data class RoadStepDto(val index: Int, val at: Int, val reward: RoadRewardDto, val reached: Boolean, val claimed: Boolean)
/** [progress]: trophies (TROPHY) or club level (LEVEL). */
@Serializable data class RoadDto(val road: String, val progress: Int, val steps: List<RoadStepDto>)
@Serializable data class RoadClaimResponse(val coins: Long, val gold: Int, val item: String?, val club: ClubDto, val road: RoadDto)
@Serializable data class CollectionDto(val id: String, val items: List<String>, val owned: List<String>, val complete: Boolean, val incomePct: Int)
@Serializable data class CollectionsResponse(val sets: List<CollectionDto>, val incomePct: Int)

fun RoadView.toDto() = RoadDto(road.name, progress, steps.map { RoadStepDto(it.step.index, it.step.at, it.step.reward.let { r -> RoadRewardDto(r.coins, r.gold, r.item) }, it.reached, it.claimed) })
fun RoadClaim.toDto() = RoadClaimResponse(coins, gold, item, club.toDto(), view.toDto())
fun Pair<List<CollectionState>, Int>.toDto() = CollectionsResponse(first.map { CollectionDto(it.set.id, it.set.items, it.owned, it.complete, it.set.incomePct) }, second)
