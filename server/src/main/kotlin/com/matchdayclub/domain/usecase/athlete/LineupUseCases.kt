package com.matchdayclub.domain.usecase.athlete

import com.matchdayclub.common.time.Clock
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.Athlete
import com.matchdayclub.domain.model.Lineup
import com.matchdayclub.domain.model.Sport
import com.matchdayclub.domain.repository.LineupRepository
import com.matchdayclub.domain.rules.Sections
import com.matchdayclub.domain.usecase.club.ClubService
import com.matchdayclub.engine.model.Position
import com.matchdayclub.engine.model.Tactics
import java.time.Instant
import java.util.UUID

object LineupRules {
    const val STARTERS = 11
    const val MAX_BENCH = 7
    /** Football shapes the client can lay a lineup out in; stored in [Tactics.formation]. */
    val FORMATIONS = setOf("4-4-2", "4-3-3", "3-5-2", "4-2-3-1", "5-3-2")

    fun starters(sport: Sport) = Sections.profile(sport).startersCount
    fun maxBench(sport: Sport) = Sections.profile(sport).benchMax

    /** Football-only overload kept for callers that predate sections. */
    fun validate(starters: List<UUID>, bench: List<UUID>, squad: List<Athlete>) = validate(Sport.FOOTBALL, starters, bench, squad)

    fun validate(sport: Sport, starters: List<UUID>, bench: List<UUID>, squad: List<Athlete>) {
        val profile = Sections.profile(sport)
        if (starters.size != profile.startersCount) throw ValidationException("Exactly {0} starters required", profile.startersCount)
        if (bench.size > profile.benchMax) throw ValidationException("At most {0} on the bench", profile.benchMax)
        val all = starters + bench
        if (all.toSet().size != all.size) throw ValidationException("Duplicate athlete in lineup")
        val byId = squad.filter { it.sport == sport }.associateBy { it.id }
        if (all.any { it !in byId }) throw ValidationException("Athlete does not belong to this section")
        for ((pos, range) in profile.requiredRoles) {
            val n = starters.count { byId.getValue(it).position == pos }
            if (n !in range) throw if (pos == Position.GK) ValidationException("Exactly one goalkeeper must start") else ValidationException("The lineup needs {0}..{1} × {2}", range.first, range.last, pos.name)
        }
    }

    /** Football default kept for callers that predate sections. */
    fun default(clubId: UUID, sport: Sport, squad: List<Athlete>, now: Instant): Lineup = defaultFor(clubId, sport, squad, now)

    /**
     * Best available lineup: fit athletes first (injured only when nobody else is left), required roles filled first,
     * then by overall; basketball keeps a guard/forward/centre balance when possible. Bench = next best.
     */
    fun defaultFor(clubId: UUID, sport: Sport, squad: List<Athlete>, now: Instant): Lineup {
        val profile = Sections.profile(sport)
        val pool = squad.filter { it.sport == sport }.sortedWith(compareBy<Athlete> { it.isInjured(now) }.thenByDescending { it.toEngine(now).effective })
        val picked = mutableListOf<Athlete>()
        for ((pos, range) in profile.requiredRoles) pool.filter { it.position == pos && it !in picked }.take(range.first).forEach { picked += it }
        if (sport == Sport.BASKETBALL) for (pos in listOf(Position.C, Position.G, Position.G, Position.F, Position.F)) {
            if (picked.size >= profile.startersCount) break
            pool.firstOrNull { it.position == pos && it !in picked }?.let { picked += it }
        }
        val maxPerRole = profile.requiredRoles.mapValues { it.value.last }
        for (a in pool) {
            if (picked.size >= profile.startersCount) break
            if (a in picked) continue
            val cap = maxPerRole[a.position]
            if (cap != null && picked.count { it.position == a.position } >= cap) continue
            picked += a
        }
        val bench = pool.filter { it !in picked }.take(profile.benchMax)
        return Lineup(clubId, sport, picked.map { it.id }, bench.map { it.id }, Tactics(), now, isDefault = true)
    }

    /**
     * The lineup that actually plays: the stored one, with injured starters replaced by the best fit bench/squad player
     * of the same role (spec §4 "травмирован — автозамена"), or the default when the stored one no longer validates.
     */
    fun effective(clubId: UUID, sport: Sport, stored: Lineup?, squad: List<Athlete>, now: Instant): Lineup {
        val valid = stored?.takeIf { runCatching { validate(sport, it.starters, it.bench, squad) }.isSuccess } ?: return defaultFor(clubId, sport, squad, now)
        val byId = squad.associateBy { it.id }
        val required = Sections.profile(sport).requiredRoles.keys
        val used = valid.starters.toMutableSet()
        val starters = valid.starters.map { id ->
            val a = byId.getValue(id)
            if (!a.isInjured(now)) id else {
                // same role first; a required role (goalkeeper) can only be covered by the same role, others by any free role
                val sub = (valid.bench.mapNotNull { byId[it] } + squad.filter { it.sport == sport })
                    .filter { it.id !in used && !it.isInjured(now) && (it.position == a.position || (a.position !in required && it.position !in required)) }
                    .maxWithOrNull(compareBy<Athlete> { it.position == a.position }.thenBy { it.toEngine(now).effective })
                if (sub == null) id else { used += sub.id; sub.id }
            }
        }
        return valid.copy(starters = starters, bench = valid.bench.filter { it !in used })
    }
}

class GetLineupUseCase(private val service: ClubService, private val squads: SquadService, private val lineups: LineupRepository, private val clock: Clock) {
    suspend operator fun invoke(userId: UUID, sport: Sport): Pair<Lineup, List<Athlete>> {
        val club = service.clubOf(userId)
        val squad = squads.squad(userId).athletes
        val stored = lineups.find(club.id, sport)?.takeIf { l -> runCatching { LineupRules.validate(sport, l.starters, l.bench, squad) }.isSuccess }
        return (stored ?: LineupRules.defaultFor(club.id, sport, squad, clock.now())) to squad.filter { it.sport == sport }
    }
}

class SetLineupUseCase(private val service: ClubService, private val squads: SquadService, private val lineups: LineupRepository, private val clock: Clock, private val progress: com.matchdayclub.domain.usecase.economy.ProgressTracker = com.matchdayclub.domain.usecase.economy.ProgressTracker { _, _ -> }) {
    suspend operator fun invoke(userId: UUID, sport: Sport, starters: List<UUID>, bench: List<UUID>, tactics: Tactics): Lineup {
        val club = service.clubOf(userId)
        LineupRules.validate(sport, starters, bench, squads.squad(userId).athletes)
        if (tactics.formation !in LineupRules.FORMATIONS) throw ValidationException("Unknown formation")
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
