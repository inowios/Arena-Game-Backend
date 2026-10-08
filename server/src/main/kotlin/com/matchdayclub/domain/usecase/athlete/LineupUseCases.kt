package com.matchdayclub.domain.usecase.athlete

import com.matchdayclub.common.time.Clock
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.Athlete
import com.matchdayclub.domain.model.Lineup
import com.matchdayclub.domain.model.Sport
import com.matchdayclub.domain.repository.LineupRepository
import com.matchdayclub.domain.usecase.club.ClubService
import com.matchdayclub.engine.model.Position
import com.matchdayclub.engine.model.Tactics
import java.time.Instant
import java.util.UUID

object LineupRules {
    const val STARTERS = 11
    const val MAX_BENCH = 7

    fun validate(starters: List<UUID>, bench: List<UUID>, squad: List<Athlete>) {
        if (starters.size != STARTERS) throw ValidationException("Exactly $STARTERS starters required")
        if (bench.size > MAX_BENCH) throw ValidationException("At most $MAX_BENCH on the bench")
        val all = starters + bench
        if (all.toSet().size != all.size) throw ValidationException("Duplicate athlete in lineup")
        val byId = squad.associateBy { it.id }
        if (all.any { it !in byId }) throw ValidationException("Athlete does not belong to the club")
        if (starters.count { byId.getValue(it).position == Position.GK } != 1) throw ValidationException("Exactly one goalkeeper must start")
    }

    /** Best 11 by overall with one GK, rest by overall; bench = next 7. */
    fun default(clubId: UUID, sport: Sport, squad: List<Athlete>, now: Instant): Lineup {
        val sorted = squad.sortedByDescending { it.overall }
        val gk = sorted.firstOrNull { it.position == Position.GK }
        val out = sorted.filter { it.position != Position.GK }.take(STARTERS - 1)
        val starters = listOfNotNull(gk) + out
        val bench = sorted.filter { it !in starters }.take(MAX_BENCH)
        return Lineup(clubId, sport, starters.map { it.id }, bench.map { it.id }, Tactics(), now, isDefault = true)
    }
}

class GetLineupUseCase(private val service: ClubService, private val squads: SquadService, private val lineups: LineupRepository, private val clock: Clock) {
    suspend operator fun invoke(userId: UUID, sport: Sport): Pair<Lineup, List<Athlete>> {
        val club = service.clubOf(userId)
        val squad = squads.squad(userId).athletes
        val stored = lineups.find(club.id, sport)?.takeIf { l -> runCatching { LineupRules.validate(l.starters, l.bench, squad) }.isSuccess }
        return (stored ?: LineupRules.default(club.id, sport, squad, clock.now())) to squad
    }
}

class SetLineupUseCase(private val service: ClubService, private val squads: SquadService, private val lineups: LineupRepository, private val clock: Clock, private val progress: com.matchdayclub.domain.usecase.economy.ProgressTracker = com.matchdayclub.domain.usecase.economy.ProgressTracker { _, _ -> }) {
    suspend operator fun invoke(userId: UUID, sport: Sport, starters: List<UUID>, bench: List<UUID>, tactics: Tactics): Lineup {
        val club = service.clubOf(userId)
        LineupRules.validate(starters, bench, squads.squad(userId).athletes)
        val saved = lineups.upsert(Lineup(club.id, sport, starters, bench, tactics, clock.now()))
        progress.record(club.id, com.matchdayclub.domain.rules.ProgressEvent.LINEUP_SAVED)
        return saved
    }
}

/** Converts a club lineup into what the match engine consumes. */
object EngineBridge {
    fun toEngineLineup(teamId: String, lineup: Lineup, squad: List<Athlete>, now: Instant): com.matchdayclub.engine.model.Lineup {
        val byId = squad.associateBy { it.id }
        return com.matchdayclub.engine.model.Lineup(
            teamId,
            lineup.starters.mapNotNull { byId[it]?.toEngine(now) },
            lineup.bench.mapNotNull { byId[it]?.toEngine(now) },
            lineup.tactics,
        )
    }
}
