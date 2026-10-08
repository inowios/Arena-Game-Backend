package com.matchdayclub.domain.usecase.economy

import com.matchdayclub.common.time.Clock
import com.matchdayclub.domain.model.DailyTask
import com.matchdayclub.domain.model.League
import com.matchdayclub.domain.model.LeagueKind
import com.matchdayclub.domain.repository.EconomyRepository
import com.matchdayclub.domain.repository.LeagueRepository
import com.matchdayclub.domain.rules.Economy
import com.matchdayclub.domain.rules.ProgressEvent
import com.matchdayclub.domain.rules.TaskKind
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Other modules report what happened; the economy decides what it means. Never throws. */
fun interface ProgressTracker { suspend fun record(clubId: UUID, event: ProgressEvent) }

class EconomyService(private val economy: EconomyRepository, private val leagues: LeagueRepository, private val clock: Clock) : ProgressTracker {
    fun today(timezone: String): LocalDate = clock.now().atZone(ZoneId.of(timezone)).toLocalDate()

    suspend fun publicLeague(clubId: UUID): League? = leagues.listForClub(clubId).firstOrNull { it.kind == LeagueKind.PUBLIC }

    /** Today's 3 tasks, created on first access. */
    suspend fun todaysTasks(clubId: UUID, timezone: String): List<DailyTask> {
        val day = today(timezone)
        val stored = economy.tasks(clubId, day)
        if (stored.isNotEmpty()) return stored
        val league = publicLeague(clubId)
        val matchToday = league != null && league.currentRound < 28
        return Economy.tasksFor(clubId, day, matchToday).map { economy.upsertTask(DailyTask(clubId, day, it.name, 0, false)) }
    }

    override suspend fun record(clubId: UUID, event: ProgressEvent) {
        val kind = event.task ?: return
        try {
            val league = publicLeague(clubId)
            val tz = league?.timezone ?: "UTC"
            val tasks = todaysTasks(clubId, tz)
            val t = tasks.firstOrNull { it.task == kind.name } ?: return
            if (t.progress >= kind.target) return
            economy.upsertTask(t.copy(progress = t.progress + 1))
        } catch (e: Exception) {
            org.slf4j.LoggerFactory.getLogger("EconomyService").warn("progress record failed", e)
        }
    }
}
