package com.arenacity.presentation.dto

import com.arenacity.domain.usecase.club.ZoneView
import kotlinx.serialization.Serializable

@Serializable data class ZoneDto(
    val sport: String, val state: String, val priceCoins: Long, val requiredStadium: Int, val restoreSeconds: Long,
    val restoreEndsAt: String?, val secondsLeft: Long, val soon: Boolean,
)

fun ZoneView.toDto() = ZoneDto(spec.sport.name, state.name, spec.priceCoins, spec.requiredStadium, spec.restoreSeconds, restoreEndsAt?.toString(), secondsLeft, spec.soon)
