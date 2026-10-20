package com.arenacity.domain.usecase.league

import com.arenacity.common.time.Clock
import com.arenacity.core.push.FcmSender
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.model.*
import com.arenacity.domain.repository.*
import com.arenacity.domain.rules.ClubLevels
import com.arenacity.domain.rules.Divisions
import com.arenacity.domain.rules.LeagueRules
import com.arenacity.domain.rules.Standings
import java.time.Instant
import java.util.UUID

/**
 * Closes a public league after its 28th round: final table → prizes, history rows and trophies, promotion/relegation,
 * then every human member is placed into next season's league of their (new) division. The finished league stays
 * readable as history. Idempotent per club and league (history row is the marker).
 */
class SeasonService(
    private val leagues: LeagueRepository,
    private val fixtures: FixtureRepository,
    private val clubs: ClubRepository,
    private val seasons: SeasonRepository,
    private val leagueService: LeagueService,
    private val clock: Clock,
    private val push: FcmSender? = null,
    private val inbox: com.arenacity.domain.usecase.account.InboxService? = null,
) : SeasonCloser {

    override suspend fun close(league: League, now: Instant): SeasonClose {
        if (league.status == LeagueStatus.FINISHED) return SeasonClose(league)
        val members = leagues.members(league.id)
        val table = Standings.table(fixtures.listByLeagueSeason(league.id, league.seasonNumber), members)
        val finished = leagues.update(league.copy(status = LeagueStatus.FINISHED, updatedAt = now))
        val humans = mutableListOf<Pair<Club, ClubSeason>>()
        table.forEachIndexed { i, row ->
            val position = i + 1
            val club = clubs.lockById(row.clubId) ?: return@forEachIndexed
            if (seasons.seasonFor(club.id, league.id) != null) return@forEachIndexed
            val movement = Divisions.movement(position, table.size, league.division)
            val newDivision = Divisions.newDivision(league.division, movement)
            val prize = Divisions.prize(position, league.division)
            val record = seasons.createSeason(ClubSeason(
                UUID.randomUUID(), club.id, league.id, club.seasonNo, league.division, position, row.played, row.won, row.drawn, row.lost, row.gf, row.ga, row.points,
                movement, newDivision, if (club.isBot) 0 else prize.coins, if (club.isBot) 0 else prize.gold, if (club.isBot) 0 else prize.reputation, now,
            ))
            if (club.isBot) return@forEachIndexed
            if (position == 1) seasons.createTrophy(Trophy(UUID.randomUUID(), club.id, TrophyKind.LEAGUE_TITLE, league.division, club.seasonNo, now))
            if (movement == Movement.PROMOTED) seasons.createTrophy(Trophy(UUID.randomUUID(), club.id, TrophyKind.PROMOTION, newDivision, club.seasonNo, now))
            val rep = club.reputation + prize.reputation
            val updated = clubs.update(club.copy(
                division = newDivision, seasonNo = club.seasonNo + 1, coins = club.coins + prize.coins, gold = club.gold + prize.gold,
                reputation = rep, level = maxOf(club.level, ClubLevels.levelFor(rep)), tzBucket = LeagueRules.tzBucket(club.timezone, now), updatedAt = now,
            ))
            humans += updated to record
        }
        for ((club, _) in humans) leagueService.ensurePublicMembership(club, now)
        val after: List<suspend () -> Unit> = humans.map { (club, rec) -> suspend { notify(club, rec) } }
        return SeasonClose(finished, after)
    }

    private suspend fun notify(club: Club, rec: ClubSeason) {
        inbox?.add(club.id, "SEASON_END", mapOf("seasonNo" to rec.seasonNo.toString(), "position" to rec.position.toString(), "movement" to rec.movement.name,
            "division" to rec.newDivision.toString(), "coins" to rec.prizeCoins.toString(), "gold" to rec.prizeGold.toString()))
        val p = push ?: return
        p.sendLocalized(listOf(club.userId), mapOf("type" to "SEASON_END", "seasonNo" to rec.seasonNo.toString())) { lang -> message(rec, lang) }
    }

    /** Season-end push text in [lang]. */
    fun message(rec: ClubSeason, lang: String): Pair<String, String> {
        val division = com.arenacity.common.i18n.Named("division.${rec.newDivision}")
        val body = when (rec.movement) {
            Movement.PROMOTED -> com.arenacity.common.i18n.ServerI18n.t("push.season_promoted", lang, division, rec.prizeCoins, rec.prizeGold)
            Movement.RELEGATED -> com.arenacity.common.i18n.ServerI18n.t("push.season_relegated", lang, division, rec.prizeCoins)
            Movement.STAYED -> com.arenacity.common.i18n.ServerI18n.t("push.season_stayed", lang, division, rec.prizeCoins, rec.prizeGold)
        }
        return com.arenacity.common.i18n.ServerI18n.t("push.season_title", lang, rec.position) to body
    }
}

data class ClubHistory(val seasons: List<ClubSeason>, val trophies: List<Trophy>)

class GetClubHistoryUseCase(private val clubs: ClubRepository, private val seasons: SeasonRepository) {
    suspend operator fun invoke(clubId: UUID): ClubHistory {
        clubs.findById(clubId) ?: throw NotFoundException("Club not found")
        return ClubHistory(seasons.seasonsOf(clubId), seasons.trophiesOf(clubId))
    }
}
