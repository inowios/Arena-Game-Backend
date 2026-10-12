package com.arenacity.domain.model

import com.arenacity.engine.model.Tactics
import java.time.Instant
import java.util.UUID

data class Lineup(val clubId: UUID, val sport: Sport, val starters: List<UUID>, val bench: List<UUID>, val tactics: Tactics, val updatedAt: Instant, val isDefault: Boolean = false)
