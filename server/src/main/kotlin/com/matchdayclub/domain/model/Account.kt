package com.matchdayclub.domain.model

import java.time.Instant
import java.util.UUID

/** One-time code that moves an account to another device (no store login needed). */
data class TransferCode(val code: String, val userId: UUID, val expiresAt: Instant, val usedAt: Instant?, val createdAt: Instant)

/** In-app inbox entry; [kind] = MATCHDAY / SEASON_END / CHALLENGE / LEAGUE_JOIN, [dataJson] = flat string map the app renders. */
data class InboxItem(val id: UUID, val clubId: UUID, val kind: String, val dataJson: String, val createdAt: Instant, val readAt: Instant?)
