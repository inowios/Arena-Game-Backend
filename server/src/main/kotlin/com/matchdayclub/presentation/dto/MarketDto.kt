package com.matchdayclub.presentation.dto

import com.matchdayclub.domain.usecase.market.MarketView
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable data class MarketListingDto(val index: Int, val athlete: AthleteDto, val price: Long, val sold: Boolean)
@Serializable data class MarketDto(val listings: List<MarketListingDto>, val refreshAt: String, val refreshGold: Int, val coins: Long, val gold: Int)
@Serializable data class BuyListingDto(val athlete: AthleteDto, val club: ClubDto)
@Serializable data class SellAthleteDto(val coins: Long, val club: ClubDto)

fun MarketView.toDto(now: Instant) = MarketDto(listings.map { MarketListingDto(it.index, it.athlete.toDto(now), it.price, it.sold) }, refreshAt.toString(), refreshGold, club.coins, club.gold)
