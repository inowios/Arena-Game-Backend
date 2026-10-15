package com.arenacity.presentation.dto

import com.arenacity.domain.usecase.club.ZoneView
import kotlinx.serialization.Serializable

/**
 * The bridge to a people's island (rules.Zones): [people] BEAST, STONE, ANDROID, SHADOW ([sport] the same, the field older
 * clients read); [state] LOCKED (no bridge), RESTORING (being built, [secondsLeft] to go), OPEN (the people returned);
 * [priceCoins], [requiredStadium] and [restoreSeconds] the bridge's; [soon] always false (no island is «later» any more).
 */
@Serializable data class ZoneDto(
    val sport: String, val state: String, val priceCoins: Long, val requiredStadium: Int, val restoreSeconds: Long,
    val restoreEndsAt: String?, val secondsLeft: Long, val soon: Boolean = false, val people: String = sport,
)

fun ZoneView.toDto() = ZoneDto(spec.people.name, state.name, spec.priceCoins, requiredStadium, spec.restoreSeconds, restoreEndsAt?.toString(), secondsLeft, false, spec.people.name)
