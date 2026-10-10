package com.matchdayclub.presentation.dto

import com.matchdayclub.domain.usecase.liveops.*
import kotlinx.serialization.Serializable

@Serializable data class GameEventDto(val id: String, val key: String, val startsAt: String, val endsAt: String, val buildTimePct: Int, val incomePct: Int, val obstacleGoldPct: Int,
                                      val goal: String?, val goalTarget: Int, val rewardCoins: Long, val rewardGold: Int, val rewardItem: String?, val running: Boolean, val progress: Int, val claimed: Boolean)
@Serializable data class EventRewardDto(val coins: Long, val gold: Int, val item: String?, val club: ClubDto)
@Serializable data class DevMailDto(val id: String, val title: String, val body: String, val coins: Long, val gold: Int, val item: String?, val createdAt: String, val expiresAt: String, val claimed: Boolean)
@Serializable data class OfferDto(val id: String, val kind: String, val gold: Int, val coins: Long, val item: String?, val oldGold: Int, val bought: Boolean)
@Serializable data class OffersDto(val starterAvailable: Boolean, val daily: List<OfferDto>, val endsAt: String)
@Serializable data class CreateEventRequest(val key: String, val startsAt: String, val endsAt: String, val buildTimePct: Int = 100, val incomePct: Int = 100, val obstacleGoldPct: Int = 100,
                                            val goal: String? = null, val goalTarget: Int = 0, val rewardCoins: Long = 0, val rewardGold: Int = 0, val rewardItem: String? = null)
@Serializable data class SendMailRequest(val title: String, val body: String = "", val coins: Long = 0, val gold: Int = 0, val item: String? = null, val clubId: String? = null, val days: Int = 14)

fun EventView.toDto() = GameEventDto(event.id, event.key, event.startsAt.toString(), event.endsAt.toString(), event.buildTimePct, event.incomePct, event.obstacleGoldPct,
    event.goal?.name, event.goalTarget, event.rewardCoins, event.rewardGold, event.rewardItem, running, progress?.count ?: 0, progress?.claimed ?: false)
fun GameEvent.toDto() = EventView(this, null, false).toDto()
fun LiveOps.EventReward.toDto() = EventRewardDto(coins, gold, item, club.toDto())
fun LiveOps.MailView.toDto() = DevMailDto(mail.id.toString(), mail.title, mail.body, mail.coins, mail.gold, mail.item, mail.createdAt.toString(), mail.expiresAt.toString(), claimed)
fun OffersView.toDto() = OffersDto(starterAvailable, daily.map { OfferDto(it.id, it.kind, it.gold, it.coins, it.item, it.oldGold, it.bought) }, endsAt.toString())
