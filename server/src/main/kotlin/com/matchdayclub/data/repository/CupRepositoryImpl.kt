package com.matchdayclub.data.repository

import com.matchdayclub.common.extension.dbQuery
import com.matchdayclub.data.database.*
import com.matchdayclub.domain.model.Cup
import com.matchdayclub.domain.model.CupEntry
import com.matchdayclub.domain.model.CupStatus
import com.matchdayclub.domain.model.CupTie
import com.matchdayclub.domain.repository.CupRepository
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.LocalDate
import java.util.UUID

class CupRepositoryImpl : CupRepository {
    private fun ResultRow.toCup() = Cup(
        this[CupTable.id], this[CupTable.tzBucket], this[CupTable.weekStart], this[CupTable.tier], this[CupTable.division], this[CupTable.currentRound],
        CupStatus.valueOf(this[CupTable.status]), this[CupTable.winnerClubId], this[CupTable.createdAt], this[CupTable.updatedAt], this[CupTable.timezone],
    )
    private fun ResultRow.toEntry() = CupEntry(
        this[CupEntryTable.weekStart], this[CupEntryTable.clubId], this[CupEntryTable.cupId], this[CupEntryTable.tzBucket], this[CupEntryTable.seed], this[CupEntryTable.outRound],
        this[CupEntryTable.prizeCoins], this[CupEntryTable.prizeGold], this[CupEntryTable.prizeReputation], this[CupEntryTable.createdAt],
    )
    private fun ResultRow.toTie() = CupTie(this[CupTieTable.cupId], this[CupTieTable.round], this[CupTieTable.slot], this[CupTieTable.homeClubId], this[CupTieTable.awayClubId], this[CupTieTable.fixtureId], this[CupTieTable.winnerClubId])

    override suspend fun create(cup: Cup, entries: List<CupEntry>, ties: List<CupTie>) {
        dbQuery {
            CupTable.insert {
                it[id] = cup.id; it[tzBucket] = cup.tzBucket; it[weekStart] = cup.weekStart; it[tier] = cup.tier; it[division] = cup.division
                it[currentRound] = cup.currentRound; it[status] = cup.status.name; it[winnerClubId] = cup.winnerClubId; it[createdAt] = cup.createdAt; it[updatedAt] = cup.updatedAt
                it[timezone] = cup.timezone
            }
            CupEntryTable.batchInsert(entries, shouldReturnGeneratedValues = false) { e ->
                this[CupEntryTable.weekStart] = e.weekStart; this[CupEntryTable.clubId] = e.clubId; this[CupEntryTable.cupId] = e.cupId; this[CupEntryTable.tzBucket] = e.tzBucket
                this[CupEntryTable.seed] = e.seed; this[CupEntryTable.outRound] = e.outRound; this[CupEntryTable.prizeCoins] = e.prizeCoins; this[CupEntryTable.prizeGold] = e.prizeGold
                this[CupEntryTable.prizeReputation] = e.prizeReputation; this[CupEntryTable.createdAt] = e.createdAt
            }
            insertTies(ties)
        }
    }

    private fun insertTies(ties: List<CupTie>) {
        CupTieTable.batchInsert(ties, shouldReturnGeneratedValues = false) { t ->
            this[CupTieTable.cupId] = t.cupId; this[CupTieTable.round] = t.round; this[CupTieTable.slot] = t.slot; this[CupTieTable.homeClubId] = t.homeClubId
            this[CupTieTable.awayClubId] = t.awayClubId; this[CupTieTable.fixtureId] = t.fixtureId; this[CupTieTable.winnerClubId] = t.winnerClubId
        }
    }

    override suspend fun find(id: UUID) = dbQuery { CupTable.selectAll().where { CupTable.id eq id }.singleOrNull()?.toCup() }
    override suspend fun lock(id: UUID) = dbQuery { CupTable.selectAll().where { CupTable.id eq id }.forUpdate().singleOrNull()?.toCup() }
    override suspend fun update(cup: Cup) = dbQuery {
        CupTable.update({ CupTable.id eq cup.id }) { it[currentRound] = cup.currentRound; it[status] = cup.status.name; it[winnerClubId] = cup.winnerClubId; it[updatedAt] = cup.updatedAt }
        cup
    }
    override suspend fun cupsOf(tzBucket: Int, weekStart: LocalDate) = dbQuery {
        CupTable.selectAll().where { (CupTable.tzBucket eq tzBucket) and (CupTable.weekStart eq weekStart) }.orderBy(CupTable.tier).map { it.toCup() }
    }
    override suspend fun active() = dbQuery { CupTable.selectAll().where { CupTable.status eq CupStatus.ACTIVE.name }.map { it.toCup() } }

    override suspend fun entry(weekStart: LocalDate, clubId: UUID) = dbQuery {
        CupEntryTable.selectAll().where { (CupEntryTable.weekStart eq weekStart) and (CupEntryTable.clubId eq clubId) }.singleOrNull()?.toEntry()
    }
    override suspend fun entries(cupId: UUID) = dbQuery { CupEntryTable.selectAll().where { CupEntryTable.cupId eq cupId }.orderBy(CupEntryTable.seed).map { it.toEntry() } }
    override suspend fun updateEntry(entry: CupEntry) {
        dbQuery {
            CupEntryTable.update({ (CupEntryTable.weekStart eq entry.weekStart) and (CupEntryTable.clubId eq entry.clubId) }) {
                it[outRound] = entry.outRound; it[prizeCoins] = entry.prizeCoins; it[prizeGold] = entry.prizeGold; it[prizeReputation] = entry.prizeReputation
            }
        }
    }
    override suspend fun lastEntryBefore(clubId: UUID, beforeWeek: LocalDate) = dbQuery {
        CupEntryTable.selectAll().where { (CupEntryTable.clubId eq clubId) and (CupEntryTable.weekStart less beforeWeek) }
            .orderBy(CupEntryTable.weekStart, SortOrder.DESC).limit(1).singleOrNull()?.toEntry()
    }
    override suspend fun weekEntrants(weekStart: LocalDate) = dbQuery {
        CupEntryTable.select(CupEntryTable.clubId).where { CupEntryTable.weekStart eq weekStart }.map { it[CupEntryTable.clubId] }.toSet()
    }

    override suspend fun ties(cupId: UUID) = dbQuery { CupTieTable.selectAll().where { CupTieTable.cupId eq cupId }.orderBy(CupTieTable.round to SortOrder.ASC, CupTieTable.slot to SortOrder.ASC).map { it.toTie() } }
    override suspend fun addTies(ties: List<CupTie>) { dbQuery { insertTies(ties) } }
    override suspend fun setWinner(cupId: UUID, round: Int, slot: Int, winnerClubId: UUID) {
        dbQuery { CupTieTable.update({ (CupTieTable.cupId eq cupId) and (CupTieTable.round eq round) and (CupTieTable.slot eq slot) }) { it[this.winnerClubId] = winnerClubId } }
    }

    override suspend fun activeHumanClubs(since: LocalDate) = dbQuery {
        ClubTable.join(UserDayTable, JoinType.INNER, ClubTable.userId, UserDayTable.userId)
            .select(ClubTable.id, ClubTable.timezone).where { (ClubTable.isBot eq false) and (UserDayTable.day greaterEq since) }
            .withDistinct().map { it[ClubTable.id] to it[ClubTable.timezone] }
    }
}
