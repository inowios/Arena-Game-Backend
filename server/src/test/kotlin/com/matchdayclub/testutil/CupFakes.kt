package com.matchdayclub.testutil

import com.matchdayclub.domain.model.Cup
import com.matchdayclub.domain.model.CupEntry
import com.matchdayclub.domain.model.CupStatus
import com.matchdayclub.domain.model.CupTie
import com.matchdayclub.domain.repository.CupRepository
import java.time.LocalDate
import java.util.UUID

class FakeCupRepository : CupRepository {
    val cups = linkedMapOf<UUID, Cup>()
    val entries = linkedMapOf<Pair<LocalDate, UUID>, CupEntry>()
    val ties = mutableListOf<CupTie>()
    /** What activeHumanClubs returns: (clubId, tzBucket) of clubs whose owners were active. */
    var active: List<Pair<UUID, Int>> = emptyList()

    override suspend fun create(cup: Cup, entries: List<CupEntry>, ties: List<CupTie>) {
        require(cups.values.none { it.tzBucket == cup.tzBucket && it.weekStart == cup.weekStart && it.tier == cup.tier }) { "duplicate cup" }
        cups[cup.id] = cup
        for (e in entries) { require((e.weekStart to e.clubId) !in this.entries) { "club in two cups" }; this.entries[e.weekStart to e.clubId] = e }
        this.ties += ties
    }
    override suspend fun find(id: UUID) = cups[id]
    override suspend fun lock(id: UUID) = cups[id]
    override suspend fun update(cup: Cup) = cup.also { cups[it.id] = it }
    override suspend fun cupsOf(tzBucket: Int, weekStart: LocalDate) = cups.values.filter { it.tzBucket == tzBucket && it.weekStart == weekStart }.sortedBy { it.tier }
    override suspend fun active() = cups.values.filter { it.status == CupStatus.ACTIVE }
    override suspend fun entry(weekStart: LocalDate, clubId: UUID) = entries[weekStart to clubId]
    override suspend fun entries(cupId: UUID) = entries.values.filter { it.cupId == cupId }.sortedBy { it.seed }
    override suspend fun updateEntry(entry: CupEntry) { entries[entry.weekStart to entry.clubId] = entry }
    override suspend fun lastEntryBefore(clubId: UUID, beforeWeek: LocalDate) = entries.values.filter { it.clubId == clubId && it.weekStart.isBefore(beforeWeek) }.maxByOrNull { it.weekStart }
    override suspend fun weekEntrants(weekStart: LocalDate) = entries.values.filter { it.weekStart == weekStart }.map { it.clubId }.toSet()
    override suspend fun ties(cupId: UUID) = ties.filter { it.cupId == cupId }.sortedWith(compareBy({ it.round }, { it.slot }))
    override suspend fun addTies(ties: List<CupTie>) { this.ties += ties }
    override suspend fun setWinner(cupId: UUID, round: Int, slot: Int, winnerClubId: UUID) {
        val i = ties.indexOfFirst { it.cupId == cupId && it.round == round && it.slot == slot }
        ties[i] = ties[i].copy(winnerClubId = winnerClubId)
    }
    override suspend fun activeHumanClubs(since: LocalDate) = active
}
