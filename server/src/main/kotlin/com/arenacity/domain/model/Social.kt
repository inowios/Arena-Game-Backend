package com.arenacity.domain.model

import java.time.Instant
import java.util.UUID

enum class MessageKind { USER, SYSTEM }

/** Chat line in a private league. [clubId] null for system lines (joins, restarts). */
data class LeagueMessage(val id: UUID, val leagueId: UUID, val clubId: UUID?, val kind: MessageKind, val text: String, val deleted: Boolean, val createdAt: Instant)

data class PrivateLeagueSeason(val id: UUID, val leagueId: UUID, val seasonNumber: Int, val winnerClubId: UUID?, val tableJson: String, val endedAt: Instant)

enum class ChallengeStatus { OPEN, ACCEPTED, EXPIRED }

/** One-off friendly by link: whoever opens it first plays the challenger. */
data class Challenge(val id: UUID, val code: String, val challengerClubId: UUID, val acceptedByClubId: UUID?, val fixtureId: UUID?, val status: ChallengeStatus, val expiresAt: Instant, val createdAt: Instant)

enum class InviteKind { LEAGUE, CHALLENGE }

/** Someone joined through another club's code or link (k-factor). [newPlayer]: the invitee's club was created for it. */
data class Invite(val id: UUID, val inviterClubId: UUID, val inviteeClubId: UUID, val kind: InviteKind, val code: String, val newPlayer: Boolean, val createdAt: Instant)
