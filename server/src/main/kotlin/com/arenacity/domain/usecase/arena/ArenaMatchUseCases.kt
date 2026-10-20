package com.arenacity.domain.usecase.arena

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.Club
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.rules.Energy
import com.arenacity.domain.rules.EnergyState
import com.arenacity.domain.rules.Heroes
import com.arenacity.domain.rules.StoryMatch
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.club.EnergyUseCases
import com.arenacity.domain.usecase.hero.HeroUseCases
import com.arenacity.domain.usecase.hero.HeroView
import com.arenacity.domain.usecase.story.StoryUpdate
import com.arenacity.domain.usecase.story.StoryUseCases
import java.util.UUID

/**
 * What the device reports after an Arena match (the v4 engine, played on the device against the AI): the result,
 * [heroes] = the ids of the club's heroes who played (rules.HeroCatalog), who earn rules.Heroes.matchXp each;
 * [story] = the chapter when this was the story match of «Іскра» (rules.Story), null for any other match.
 */
data class ArenaReport(val seed: Int, val won: Boolean, val draw: Boolean, val goalsFor: Int, val goalsAgainst: Int, val stake: Int = 1, val conceded: Boolean = false,
                       val heroes: List<String> = emptyList(), val story: Int? = null)
/** [heroes] = the heroes who played as they are now, [heroXp] = what each was offered (the daily cap may have cut it), [story] = the story's current chapter after the match. */
data class ArenaOutcome(val coins: Long, val club: Club, val heroes: List<HeroView> = emptyList(), val heroXp: Int = 0, val story: StoryUpdate? = null)

/**
 * The Arena match's rewards (POST /club/arena/start and /result). For now the server trusts the device's report within
 * plausible bounds: coins by the result and the stake; the heroes who played ([ArenaReport.heroes], at most
 * rules.Heroes.MAX_HEROES_PER_MATCH) get the experience the server sets by the result, capped per hero a day; the match
 * then counts for the club's chapter of the story «Іскра» (usecase.story.StoryUseCases.record).
 */
class ArenaMatchUseCases(private val service: ClubService, private val clubs: ClubRepository, private val clock: Clock, private val tx: Tx = NoTx,
                         private val heroes: HeroUseCases? = null, private val stories: StoryUseCases? = null) {
    companion object {
        /** Coins by the result at the stake: a win pays stake times more, a loss pays stake times less; a concession pays as a plain loss. */
        fun coins(won: Boolean, draw: Boolean, stake: Int = 1, conceded: Boolean = false): Long = if (won) 120L * stake else if (draw) 50 else if (conceded) 20 else 20L / stake
    }

    /** An Arena match starts: it costs [Energy.CARD_MATCH] energy (400 when short). */
    suspend fun start(userId: UUID): EnergyState = tx {
        val club = service.lockedClubOf(userId); val now = clock.now()
        val saved = clubs.update(EnergyUseCases.spend(club, Energy.CARD_MATCH, now).copy(updatedAt = now))
        Energy.state(saved, now)
    }

    suspend fun report(userId: UUID, r: ArenaReport): ArenaOutcome = tx {
        if (r.heroes.distinct().size > Heroes.MAX_HEROES_PER_MATCH) throw ValidationException("Too many heroes")
        if (r.goalsFor !in 0..20 || r.goalsAgainst !in 0..20) throw ValidationException("Bad score")
        if (r.stake !in listOf(1, 2, 4)) throw ValidationException("Bad stake")
        val club = service.lockedClubOf(userId)
        val now = clock.now()
        val coins = coins(r.won, r.draw, r.stake, r.conceded)
        val saved = clubs.update(club.copy(coins = club.coins + coins, updatedAt = now))
        val heroXp = Heroes.matchXp(r.won, r.draw, r.conceded)
        val played = heroes?.grantMatchXp(saved, r.heroes, heroXp).orEmpty()
        // the story «Іскра» counts the match for the club's current chapter (the heroes: only its own who played)
        val story = stories?.record(saved, StoryMatch(r.won, played.map { it.spec.id }.toSet(), r.story, r.conceded))
        ArenaOutcome(coins, saved, played, heroXp, story)
    }
}
