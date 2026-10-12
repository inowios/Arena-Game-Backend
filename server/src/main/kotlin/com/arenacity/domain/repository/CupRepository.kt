package com.arenacity.domain.repository

import com.arenacity.domain.model.Cup
import com.arenacity.domain.model.CupEntry
import com.arenacity.domain.model.CupTie
import java.time.LocalDate
import java.util.UUID

interface CupRepository {
    /** Creates a drawn cup with its 16 entries and first-round ties (fixtures must exist). */
    suspend fun create(cup: Cup, entries: List<CupEntry>, ties: List<CupTie>)
    suspend fun find(id: UUID): Cup?
    /** SELECT … FOR UPDATE (one round at a time). */
    suspend fun lock(id: UUID): Cup?
    suspend fun update(cup: Cup): Cup
    suspend fun cupsOf(tzBucket: Int, weekStart: LocalDate): List<Cup>
    suspend fun active(): List<Cup>

    suspend fun entry(weekStart: LocalDate, clubId: UUID): CupEntry?
    suspend fun entries(cupId: UUID): List<CupEntry>
    suspend fun updateEntry(entry: CupEntry)
    /** The club's most recent entry before [beforeWeek]. */
    suspend fun lastEntryBefore(clubId: UUID, beforeWeek: LocalDate): CupEntry?
    /** Clubs (humans and bots) already drawn into some cup of that week. */
    suspend fun weekEntrants(weekStart: LocalDate): Set<UUID>

    suspend fun ties(cupId: UUID): List<CupTie>
    suspend fun addTies(ties: List<CupTie>)
    suspend fun setWinner(cupId: UUID, round: Int, slot: Int, winnerClubId: UUID)

    /** Human clubs whose owner was active on or after [since] (UTC day), with their time zone. */
    suspend fun activeHumanClubs(since: LocalDate): List<Pair<UUID, String>>
}
