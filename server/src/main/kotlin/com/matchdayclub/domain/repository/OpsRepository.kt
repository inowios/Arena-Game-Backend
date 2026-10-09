package com.matchdayclub.domain.repository

import com.matchdayclub.domain.model.Club
import com.matchdayclub.domain.model.Purchase
import java.time.LocalDate
import java.util.UUID

/** Read-only queries for the admin/support endpoints. */
interface OpsRepository {
    /** Row counts for the ops overview (users, clubs, bots, leagues, fixtures, cups, purchases, push tokens). */
    suspend fun counts(): Map<String, Long>
    /** Human and bot clubs whose name contains [q] (case-insensitive), or the club with that id. */
    suspend fun searchClubs(q: String, limit: Int = 20): List<Club>
    suspend fun purchasesOf(clubId: UUID): List<Purchase>
    suspend fun lastActive(userId: UUID): LocalDate?
}
