package com.arenacity.domain.model

import java.time.Instant
import java.util.UUID

data class Club(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val primaryColor: String,
    val secondaryColor: String,
    val emblemJson: String?,
    val level: Int,
    val reputation: Int,
    val coins: Long,
    val gold: Int,
    val timezone: String,
    val academyNextAt: Instant?,
    val medicalNextAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val isBot: Boolean = false,
    val division: Int = 1,
    val tzBucket: Int = 0,
    val friendliesDate: java.time.LocalDate? = null,
    val friendliesPlayed: Int = 0,
    val loginDay: Int = 0,
    val loginLast: java.time.LocalDate? = null,
    val streak: Int = 0,
    /** The club's own season counter (1 = first season); keys the season pass and history. */
    val seasonNo: Int = 1,
    /** Equipped cosmetics (null = default look). */
    val kit: String? = null,
    val emblemFrame: String? = null,
    val celebration: String? = null,
    /** Club-local day of the last scouting signing (one a day). */
    val scoutSignedDate: java.time.LocalDate? = null,
    /** Live-match trophies (arena ladder) and the current run of live wins (a player pack every third). */
    val trophies: Int = 0,
    val liveStreak: Int = 0,
    /** Bumped by a paid market refresh: a new set of listings for the same period. */
    val marketSalt: Int = 0,
    /** ISO week (year*100 + week) the arena chest was last opened in. */
    val arenaChestWeek: Int = 0,
    /** Energy (rules.Energy): the amount at [energyAt], refilling on read; null [energyAt] = full. */
    val energy: Int = com.arenacity.domain.rules.Energy.MAX,
    val energyAt: Instant? = null,
    /** The UTC day the club last bought the daily pack offer (rules.DailyOffers; once a day). */
    val offerDay: java.time.LocalDate? = null,
    /**
     * When the owner was last seen (an authenticated request; usecase.club.PresenceTracker, at most once a minute); null =
     * not since V42. Read-only in [com.arenacity.domain.repository.ClubRepository.update]: only touchSeen writes it.
     */
    val lastSeenAt: Instant? = null,
)
