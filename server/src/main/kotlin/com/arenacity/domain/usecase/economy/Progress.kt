package com.arenacity.domain.usecase.economy

import com.arenacity.common.time.Clock
import com.arenacity.domain.model.DailyTask
import com.arenacity.domain.model.League
import com.arenacity.domain.model.LeagueKind
import com.arenacity.domain.repository.EconomyRepository
import com.arenacity.domain.repository.LeagueRepository
import com.arenacity.domain.rules.Economy
import com.arenacity.domain.rules.ProgressEvent
import com.arenacity.domain.rules.TaskKind
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Other modules report what happened; the economy decides what it means. Never throws. */
fun interface ProgressTracker { suspend fun record(clubId: UUID, event: ProgressEvent) }

class EconomyService(
    private val economy: EconomyRepository,
    private val leagues: LeagueRepository,
    private val clock: Clock,
    private val sectionCount: (suspend (UUID) -> Int)? = null,
    private val achievements: com.arenacity.domain.repository.AchievementRepository? = null,
) : ProgressTracker {
    /** Other listeners of the same events (the alliance weekend tournament counts arena wins). */
    var also: List<ProgressTracker> = emptyList()
    fun today(timezone: String): LocalDate = clock.now().atZone(ZoneId.of(timezone)).toLocalDate()

    suspend fun publicLeague(clubId: UUID): League? = leagues.listForClub(clubId).firstOrNull { it.kind == LeagueKind.PUBLIC && it.status == com.arenacity.domain.model.LeagueStatus.ACTIVE }

    /** Today's 3 tasks, created on first access. */
    suspend fun todaysTasks(clubId: UUID, timezone: String): List<DailyTask> {
        val day = today(timezone)
        // a task that went (collecting income went with the city builder) is left out of a day stored before
        val stored = economy.tasks(clubId, day)
        if (stored.isNotEmpty()) return stored.filter { t -> TaskKind.entries.any { it.name == t.task } }
        val league = publicLeague(clubId)
        val matchToday = league != null && league.currentRound < 28
        val multi = (sectionCount?.invoke(clubId) ?: 1) >= 2
        return Economy.tasksFor(clubId, day, matchToday, multi).map { economy.upsertTask(DailyTask(clubId, day, it.name, 0, false)) }
    }

    override suspend fun record(clubId: UUID, event: ProgressEvent) {
        also.forEach { runCatching { it.record(clubId, event) } }
        event.metric?.let { m -> achievements?.let { a -> runCatching { a.increment(clubId, m) }.onFailure { org.slf4j.LoggerFactory.getLogger("EconomyService").warn("counter failed", it) } } }
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

    /** Running maximum of a metric (best login streak). */
    suspend fun recordMax(clubId: UUID, metric: com.arenacity.domain.rules.Metric, value: Long) {
        achievements?.let { a -> runCatching { a.raiseTo(clubId, metric, value) }.onFailure { org.slf4j.LoggerFactory.getLogger("EconomyService").warn("counter failed", it) } }
    }
}
