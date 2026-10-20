package com.arenacity.domain.usecase.arena

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.ArenaMatch
import com.arenacity.domain.model.ArenaMatchStatus
import com.arenacity.domain.model.Club
import com.arenacity.domain.repository.ArenaMatchRepository
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.rules.Arena
import com.arenacity.domain.rules.Arenas
import com.arenacity.domain.rules.Economy
import com.arenacity.domain.rules.Energy
import com.arenacity.domain.rules.EnergyState
import com.arenacity.domain.rules.Heroes
import com.arenacity.domain.rules.ProgressEvent
import com.arenacity.domain.rules.Story
import com.arenacity.domain.rules.StoryMatch
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.club.EnergyUseCases
import com.arenacity.domain.usecase.economy.ProgressTracker
import com.arenacity.domain.usecase.hero.HeroUseCases
import com.arenacity.domain.usecase.hero.HeroView
import com.arenacity.domain.usecase.story.StoryUpdate
import com.arenacity.domain.usecase.story.StoryUseCases
import java.time.ZoneOffset
import java.util.UUID

/** A match handed out: the energy left and the match (its id is the token the result carries). */
data class ArenaStart(val energy: EnergyState, val match: ArenaMatch)

/**
 * What the device reports after an Arena match (the v4 engine, 3 v 3, played on the device against the AI): the
 * [matchId] of POST /club/arena/start, the result ([won]; [draw] is refused — the Arena v4 has none; [conceded] = the
 * player surrendered), [heroes] = the ids of the club's heroes who played (its triple, rules.HeroCatalog), who earn
 * rules.Heroes.matchXp each, [knockouts] = the rival heroes taken out (0..3), [altSkills] = the alternate skills used.
 */
data class ArenaReport(val matchId: UUID, val won: Boolean, val draw: Boolean = false, val conceded: Boolean = false, val heroes: List<String> = emptyList(),
                       val knockouts: Int = 0, val altSkills: Int = 0, val seed: Int = 0)
/**
 * [coins] paid ([rewarded] false once the day's [Economy.ARENA_REWARDED_PER_DAY] are used up: 0 then), [rewardedToday]
 * after this match, the trophies and the arena now, [heroes] = the heroes who played as they are now, [heroXp] = what
 * each was offered (the daily cap may have cut it), [story] = the story's current chapter after the match.
 */
data class ArenaOutcome(val match: ArenaMatch, val coins: Long, val rewarded: Boolean, val rewardedToday: Int, val club: Club, val trophiesDelta: Int, val arena: Arena,
                        val heroes: List<HeroView> = emptyList(), val heroXp: Int = 0, val story: StoryUpdate? = null)

/**
 * The Arena match (POST /club/arena/start and /result; docs/claude/ARENA-MATCH-V3.md in the mobile repo). The start takes
 * the energy and hands out a match token with its stake and story chapter; the result must name that token and is taken
 * once. The server trusts the device's result within bounds: coins by the result and the stake (at most
 * [Economy.ARENA_REWARDED_PER_DAY] paid matches a day), trophies up and down the ladder (rules.Arenas), the win streak,
 * experience for the heroes who played (capped per hero a day), the story's chapter (only the match started for it
 * counts as its story match), and the progress events the daily tasks, achievements, the alliance tournament and the
 * running events listen to.
 */
class ArenaMatchUseCases(private val service: ClubService, private val clubs: ClubRepository, private val matches: ArenaMatchRepository, private val clock: Clock,
                         private val tx: Tx = NoTx, private val heroes: HeroUseCases? = null, private val stories: StoryUseCases? = null,
                         private val progress: ProgressTracker = ProgressTracker { _, _ -> }) {
    companion object {
        val STAKES = listOf(1, 2, 4)
        /** Coins by the result at the stake: a win pays stake times more, a loss pays stake times less; a concession pays as a plain loss. */
        fun coins(won: Boolean, stake: Int = 1, conceded: Boolean = false): Long = if (won) 120L * stake else if (conceded) 20 else 20L / stake
        const val MAX_KNOCKOUTS = Heroes.MAX_HEROES_PER_MATCH
        const val MAX_ALT_SKILLS = 40
    }

    /**
     * An Arena match starts: [stake] 1 / 2 / 4, [story] = the chapter when this is its story match (it must be the
     * club's current chapter). It costs [Energy.CARD_MATCH] energy (400 when short); a match of the club still open is
     * abandoned (no result, no reward: the device left it).
     */
    suspend fun start(userId: UUID, stake: Int = 1, story: Int? = null): ArenaStart = tx {
        if (stake !in STAKES) throw ValidationException("Bad stake")
        val club = service.lockedClubOf(userId); val now = clock.now()
        if (story != null) {
            val current = stories?.currentChapter(club.id) ?: 0
            if (story != current || Story.chapter(story) == null) throw ValidationException("Not your current chapter")
        }
        for (open in matches.findOpen(club.id)) matches.update(open.copy(status = ArenaMatchStatus.ABANDONED, finishedAt = now))
        val saved = clubs.update(EnergyUseCases.spend(club, Energy.CARD_MATCH, now).copy(updatedAt = now))
        val match = matches.create(ArenaMatch(UUID.randomUUID(), club.id, stake, story, ArenaMatchStatus.OPEN, now))
        ArenaStart(Energy.state(saved, now), match)
    }

    suspend fun report(userId: UUID, r: ArenaReport): ArenaOutcome = tx {
        if (r.draw) throw ValidationException("No draws in the Arena")
        if (r.heroes.distinct().size > Heroes.MAX_HEROES_PER_MATCH) throw ValidationException("Too many heroes")
        if (r.knockouts !in 0..MAX_KNOCKOUTS || r.altSkills !in 0..MAX_ALT_SKILLS) throw ValidationException("Implausible match report")
        val club = service.lockedClubOf(userId)
        val m = matches.find(r.matchId)?.takeIf { it.clubId == club.id } ?: throw NotFoundException("Match not found")
        if (m.status != ArenaMatchStatus.OPEN) throw ConflictException("Match already reported")
        val now = clock.now()
        val won = r.won && !r.conceded
        // the day's paid matches (UTC), then only trophies and experience
        val today = now.atZone(ZoneOffset.UTC).toLocalDate()
        val rewardedBefore = if (club.arenaDay == today) club.arenaRewarded else 0
        val rewarded = rewardedBefore < Economy.ARENA_REWARDED_PER_DAY
        val coins = if (rewarded) coins(won, m.stake, r.conceded) else 0L
        val trophies = (club.trophies + Arenas.trophies(won)).coerceAtLeast(0)
        val saved = clubs.update(club.copy(coins = club.coins + coins, trophies = trophies, liveStreak = if (won) club.liveStreak + 1 else 0,
            arenaDay = today, arenaRewarded = rewardedBefore + (if (rewarded) 1 else 0), updatedAt = now))
        val finished = matches.update(m.copy(status = ArenaMatchStatus.REPORTED, finishedAt = now, won = won))
        val heroXp = Heroes.matchXp(won, false, r.conceded)
        val played = heroes?.grantMatchXp(saved, r.heroes, heroXp).orEmpty()
        // the story «Іскра» counts the match for the club's current chapter (the heroes: only its own who played; the story match = the one started for the chapter)
        val story = stories?.record(saved, StoryMatch(won, played.map { it.spec.id }.toSet(), m.story, r.conceded))
        // the daily tasks, achievements, the alliance tournament and the running events
        progress.record(club.id, ProgressEvent.MATCH_PLAYED); progress.record(club.id, ProgressEvent.ARENA_PLAYED)
        if (won) { progress.record(club.id, ProgressEvent.MATCH_WON); progress.record(club.id, ProgressEvent.ARENA_WON) }
        repeat(r.knockouts) { progress.record(club.id, ProgressEvent.KNOCKOUT) }
        repeat(r.altSkills) { progress.record(club.id, ProgressEvent.ALT_SKILL_USED) }
        ArenaOutcome(finished, coins, rewarded, saved.arenaRewarded, saved, trophies - club.trophies, Arenas.of(trophies), played, heroXp, story)
    }
}
