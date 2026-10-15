package com.arenacity.domain.usecase.social

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.core.push.FcmSender
import com.arenacity.domain.exception.*
import com.arenacity.domain.model.*
import com.arenacity.domain.repository.*
import com.arenacity.domain.rules.LeagueRules
import com.arenacity.domain.rules.StandingRow
import com.arenacity.domain.rules.Standings
import com.arenacity.domain.usecase.athlete.SectionService
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.league.LeagueQueries
import com.arenacity.domain.usecase.league.MatchView
import com.arenacity.domain.usecase.league.MatchdayRunner
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

/** Masks a small list of strong words (all five client languages). Matching is on lower-cased word stems. */
object ProfanityFilter {
    private val stems = listOf(
        "fuck", "shit", "bitch", "cunt", "asshole", "nigg", "fag",
        "puta", "mierda", "joder", "coño", "cabron", "caralho", "porra", "merda", "viado", "buceta",
        "хуй", "хуе", "хуё", "пизд", "ебат", "ёбан", "ебан", "бляд", "сука", "мудак", "пидор", "хер",
    )
    fun clean(text: String): String {
        var out = text
        val lower = text.lowercase()
        for (s in stems) {
            var i = lower.indexOf(s)
            while (i >= 0) {
                var end = i + s.length
                while (end < lower.length && lower[end].isLetter()) end++
                out = out.substring(0, i) + "*".repeat(end - i) + out.substring(end)
                i = lower.indexOf(s, end)
            }
        }
        return out
    }
}

data class ChatLine(val message: LeagueMessage, val club: Club?)

/** Private-league chat: members only, rate limited, masked, reportable (3 reports hide a line), blockable. */
/** [channels]: other chat channels a club may use besides its private leagues (the alliance chat): (clubId, channelId) -> member. */
class ChatService(
    private val service: ClubService,
    private val leagues: LeagueRepository,
    private val clubs: ClubRepository,
    private val chat: ChatRepository,
    private val clock: Clock,
    private val tx: Tx = NoTx,
    private val channels: (suspend (UUID, UUID) -> Boolean)? = null,
) {
    companion object {
        const val MAX_LENGTH = 300
        const val MIN_INTERVAL_SECONDS = 2L
        const val BURST_WINDOW_SECONDS = 300L
        const val BURST_MAX = 20
        const val HIDE_AFTER_REPORTS = 3
    }

    private suspend fun memberLeague(club: Club, leagueId: UUID) {
        if (channels?.invoke(club.id, leagueId) == true) return
        val league = leagues.findById(leagueId)?.takeIf { it.kind == LeagueKind.PRIVATE } ?: throw NotFoundException("League not found")
        if (club.id !in leagues.members(league.id)) throw AuthorizationException("Not a member of this league")
    }

    private suspend fun withClubs(list: List<LeagueMessage>, me: Club): List<ChatLine> {
        val blocked = chat.blockedBy(me.id)
        val visible = list.filter { it.clubId == null || it.clubId !in blocked }
        val byId = clubs.findByIds(visible.mapNotNull { it.clubId }.toSet()).associateBy { it.id }
        return visible.map { ChatLine(it, it.clubId?.let(byId::get)) }
    }

    /** Newest page (older than [before]) or, for polling, everything after [after] in chronological order. */
    suspend fun list(userId: UUID, leagueId: UUID, before: Instant?, after: Instant?, limit: Int = 50): List<ChatLine> {
        val me = service.clubOf(userId)
        memberLeague(me, leagueId)
        val n = limit.coerceIn(1, 100)
        val raw = if (after != null) chat.since(leagueId, after, n) else chat.list(leagueId, before, n).reversed()
        return withClubs(raw, me)
    }

    suspend fun post(userId: UUID, leagueId: UUID, text: String): ChatLine = tx {
        val me = service.lockedClubOf(userId)
        memberLeague(me, leagueId)
        val body = text.trim().replace(Regex("\\s+"), " ")
        if (body.isEmpty() || body.length > MAX_LENGTH) throw ValidationException("A message must be 1..{0} characters", MAX_LENGTH)
        val now = clock.now()
        chat.lastByClub(me.id)?.let { if (now.epochSecond - it.createdAt.epochSecond < MIN_INTERVAL_SECONDS) throw ConflictException("Slow down") }
        if (chat.countByClubSince(me.id, now.minusSeconds(BURST_WINDOW_SECONDS)) >= BURST_MAX) throw ConflictException("Too many messages, try again in a few minutes")
        val m = chat.post(LeagueMessage(UUID.randomUUID(), leagueId, me.id, MessageKind.USER, ProfanityFilter.clean(body), false, now))
        ChatLine(m, me)
    }

    suspend fun system(leagueId: UUID, key: String, vararg args: String) {
        val text = (listOf(key) + args.map { it.replace("|", "/") }).joinToString("|")
        chat.post(LeagueMessage(UUID.randomUUID(), leagueId, null, MessageKind.SYSTEM, text.take(400), false, clock.now()))
    }

    suspend fun report(userId: UUID, messageId: UUID, reason: String) {
        val me = service.clubOf(userId)
        val m = chat.find(messageId) ?: throw NotFoundException("Message not found")
        memberLeague(me, m.leagueId)
        if (m.clubId == me.id) throw ValidationException("Cannot report your own message")
        chat.report(m.id, me.id, reason.take(40).ifBlank { "abuse" })
        if (chat.reportCount(m.id) >= HIDE_AFTER_REPORTS) chat.markDeleted(m.id)
    }

    /** The author or the league's creator can delete a line. */
    suspend fun delete(userId: UUID, messageId: UUID) {
        val me = service.clubOf(userId)
        val m = chat.find(messageId) ?: throw NotFoundException("Message not found")
        memberLeague(me, m.leagueId)
        val league = leagues.findById(m.leagueId)   // null for an alliance channel: only the author deletes there
        if (m.clubId != me.id && league?.createdBy != me.id) throw AuthorizationException("Only the author or the league creator can delete")
        chat.markDeleted(m.id)
    }

    suspend fun block(userId: UUID, clubId: UUID) { val me = service.clubOf(userId); if (me.id == clubId) throw ValidationException("Cannot block yourself"); chat.block(me.id, clubId) }
    suspend fun unblock(userId: UUID, clubId: UUID) { val me = service.clubOf(userId); chat.unblock(me.id, clubId) }
    suspend fun blocked(userId: UUID): Set<UUID> = chat.blockedBy(service.clubOf(userId).id)
}

@Serializable data class FrozenRow(val clubId: String, val name: String, val position: Int, val played: Int, val won: Int, val drawn: Int, val lost: Int, val points: Int)

/** Private-league management by its creator: restart a season (table frozen into history), remove members. */
class PrivateLeagueAdmin(
    private val service: ClubService,
    private val leagues: LeagueRepository,
    private val clubs: ClubRepository,
    private val social: SocialRepository,
    private val queries: LeagueQueries,
    private val chat: ChatService,
    private val clock: Clock,
    private val tx: Tx = NoTx,
) {
    private suspend fun ownLeague(userId: UUID, leagueId: UUID): Pair<Club, League> {
        val me = service.clubOf(userId)
        val league = leagues.lockById(leagueId)?.takeIf { it.kind == LeagueKind.PRIVATE } ?: throw NotFoundException("League not found")
        if (league.createdBy != me.id) throw AuthorizationException("Only the league creator can do this")
        return me to league
    }

    suspend fun restart(userId: UUID, leagueId: UUID): League = tx {
        val (_, league) = ownLeague(userId, leagueId)
        val view = queries.view(league)
        val now = clock.now()
        val frozen = view.table.mapIndexed { i, r -> FrozenRow(r.clubId.toString(), view.clubs[r.clubId]?.name ?: "?", i + 1, r.played, r.won, r.drawn, r.lost, r.points) }
        val winner = view.table.firstOrNull()?.takeIf { it.played > 0 }
        social.savePrivateSeason(PrivateLeagueSeason(UUID.randomUUID(), league.id, league.seasonNumber, winner?.clubId, Json.encodeToString(frozen), now))
        val restarted = leagues.update(league.copy(seasonNumber = league.seasonNumber + 1, seasonStart = now.atZone(ZoneId.of(league.timezone)).toLocalDate(), updatedAt = now))
        chat.system(league.id, "restart", restarted.seasonNumber.toString(), winner?.let { view.clubs[it.clubId]?.name } ?: "")
        restarted
    }

    suspend fun kick(userId: UUID, leagueId: UUID, clubId: UUID) = tx {
        val (me, league) = ownLeague(userId, leagueId)
        if (clubId == me.id) throw ValidationException("Use leave instead")
        if (clubId !in leagues.members(league.id)) throw NotFoundException("Not a member")
        leagues.removeMember(league.id, clubId)
        chat.system(league.id, "kick", clubs.findById(clubId)?.name ?: "?")
    }

    suspend fun history(userId: UUID, leagueId: UUID): List<PrivateLeagueSeason> {
        val me = service.clubOf(userId)
        val league = leagues.findById(leagueId)?.takeIf { it.kind == LeagueKind.PRIVATE } ?: throw NotFoundException("League not found")
        if (me.id !in leagues.members(league.id)) throw AuthorizationException("Not a member of this league")
        return social.privateSeasons(league.id)
    }
}

data class HeadToHead(val me: Club, val other: Club, val fixtures: List<Fixture>, val won: Int, val drawn: Int, val lost: Int)

class HeadToHeadUseCase(private val service: ClubService, private val clubs: ClubRepository, private val fixtures: FixtureRepository) {
    suspend operator fun invoke(userId: UUID, otherClubId: UUID): HeadToHead {
        val me = service.clubOf(userId)
        val other = clubs.findById(otherClubId) ?: throw NotFoundException("Club not found")
        val list = fixtures.listBetween(me.id, other.id, 30).filter { it.status == FixtureStatus.PLAYED }
        var w = 0; var d = 0; var l = 0
        for (f in list) {
            val mine = f.pointsFor(me.id) ?: (if (f.homeClubId == me.id) f.homeScore else f.awayScore) ?: 0
            val theirs = f.pointsFor(other.id) ?: (if (f.homeClubId == other.id) f.homeScore else f.awayScore) ?: 0
            when { mine > theirs -> w++; mine == theirs -> d++; else -> l++ }
        }
        return HeadToHead(me, other, list, w, d, l)
    }
}

/** [land]: the visited club's land (the main island, and the peoples' islands once its bridges to them stand), [lands] its mask. */
data class Grounds(val club: Club, val buildings: List<Building>, val gridSize: Int, val sections: List<Sport>, val land: com.arenacity.domain.rules.LandSet,
                   val lands: com.arenacity.domain.rules.LandMap)

/** Another club's grounds, read-only (spec §5 "прогресс друга": visit the friend's club). */
class GetGroundsUseCase(private val clubs: ClubRepository, private val buildings: BuildingRepository, private val sections: SectionService,
                        private val lands: com.arenacity.domain.rules.LandMap = com.arenacity.domain.rules.Lands.MAP) {
    /** The peoples whose island a club's bridge opened (its OPEN zones; DI), as in ClubService. */
    var openZones: suspend (UUID) -> Set<com.arenacity.domain.model.People> = { emptySet() }

    suspend operator fun invoke(clubId: UUID): Grounds {
        val club = clubs.findById(clubId) ?: throw NotFoundException("Club not found")
        val land = lands.of(openZones(club.id))
        // what stands on the club's land: a building still off it (its owner has not opened the club since the land
        // became a mask, or one the land had no room for, parked) is not shown to a visitor
        val list = buildings.listByClub(club.id).filter { land.fits(com.arenacity.domain.rules.footprint(it.type, it.gridX, it.gridY, it.rotation)) }
        return Grounds(club, list, maxOf(com.arenacity.domain.rules.ClubLevels.gridSize(club.level), com.arenacity.domain.rules.Stadium.gridSize(com.arenacity.domain.rules.Stadium.level(list))), sections.active(club, clubBuildings = list), land, lands)
    }
}

data class ChallengeView(val challenge: Challenge, val challenger: Club, val acceptedBy: Club?)

/** One-off friendly by link (spec §5 "дружеский вызов"): the first other club to open it plays the challenger. */
class ChallengeUseCases(
    private val service: ClubService,
    private val clubs: ClubRepository,
    private val fixtures: FixtureRepository,
    private val social: SocialRepository,
    private val runner: MatchdayRunner,
    private val queries: LeagueQueries,
    private val clock: Clock,
    private val tx: Tx = NoTx,
    private val push: FcmSender? = null,
    private val inbox: com.arenacity.domain.usecase.account.InboxService? = null,
) {
    companion object {
        const val TTL_SECONDS = 7 * 86_400L
        const val MAX_OPEN = 5
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }

    suspend fun create(userId: UUID): Challenge = tx {
        val me = service.lockedClubOf(userId)
        val now = clock.now()
        if (social.openChallengesOf(me.id, now).size >= MAX_OPEN) throw ConflictException("You already have {0} open challenges", MAX_OPEN)
        var code: String; var attempt = 0
        do { code = (1..8).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString(""); attempt++ } while (social.challengeByCode(code) != null && attempt < 10)
        social.createChallenge(Challenge(UUID.randomUUID(), code, me.id, null, null, ChallengeStatus.OPEN, now.plusSeconds(TTL_SECONDS), now))
    }

    suspend fun view(code: String): ChallengeView {
        val c = social.challengeByCode(code.trim().uppercase()) ?: throw NotFoundException("Challenge not found")
        val status = if (c.status == ChallengeStatus.OPEN && !clock.now().isBefore(c.expiresAt)) ChallengeStatus.EXPIRED else c.status
        val challenger = clubs.findById(c.challengerClubId) ?: throw NotFoundException("Challenge not found")
        return ChallengeView(c.copy(status = status), challenger, c.acceptedByClubId?.let { clubs.findById(it) })
    }

    suspend fun accept(userId: UUID, code: String): MatchView {
        val view = view(code)
        val c = view.challenge
        val result = tx {
            val me = service.lockedClubOf(userId)
            if (me.id == c.challengerClubId) throw ValidationException("This is your own challenge — send it to a friend")
            if (c.status != ChallengeStatus.OPEN) throw ConflictException(if (c.status == ChallengeStatus.EXPIRED) "This challenge has expired" else "This challenge was already played")
            val now = clock.now()
            val fixture = Fixture(UUID.randomUUID(), null, FixtureKind.CHALLENGE, 0, 0, now, c.challengerClubId, me.id, FixtureStatus.SCHEDULED, null, null, now.toEpochMilli() xor me.id.leastSignificantBits, null, null, now)
            fixtures.createAll(listOf(fixture))
            if (!social.acceptChallenge(c.id, me.id, fixture.id)) throw ConflictException("This challenge was already played")
            val played = runner.playFixture(fixture, now) ?: throw ConflictException("Challenge could not be played")
            social.recordInvite(Invite(UUID.randomUUID(), c.challengerClubId, me.id, InviteKind.CHALLENGE, c.code, me.createdAt.isAfter(now.minusSeconds(2 * 86_400)), now))
            me to played
        }
        val (me, played) = result
        inbox?.add(view.challenger.id, "CHALLENGE", mapOf("fixtureId" to played.fixture.id.toString(), "club" to me.name,
            "home" to (played.fixture.homeScore ?: 0).toString(), "away" to (played.fixture.awayScore ?: 0).toString()))
        push?.let { p ->
            runCatching {
                p.sendLocalized(listOf(view.challenger.userId), mapOf("type" to "CHALLENGE", "fixtureId" to played.fixture.id.toString())) { lang ->
                    com.arenacity.common.i18n.ServerI18n.t("push.challenge_title", lang) to
                        com.arenacity.common.i18n.ServerI18n.t("push.challenge_body", lang, me.name, played.fixture.homeScore, played.fixture.awayScore)
                }
            }
        }
        return MatchView(played.fixture, played.result, mapOf(view.challenger.id to view.challenger, me.id to me), queries.athleteNames(played.fixture))
    }

    suspend fun mine(userId: UUID): List<Challenge> = social.openChallengesOf(service.clubOf(userId).id, clock.now())
}

/** Records who brought whom (k-factor) and tells the inviter. Never throws into the caller's flow. */
class InviteTracker(private val social: SocialRepository, private val clubs: ClubRepository, private val clock: Clock, private val push: FcmSender? = null, private val progress: com.arenacity.domain.usecase.economy.ProgressTracker? = null, private val inbox: com.arenacity.domain.usecase.account.InboxService? = null) {
    suspend fun leagueJoined(league: League, joiner: Club) {
        val inviter = league.createdBy ?: return
        if (inviter == joiner.id) return
        val now = clock.now()
        val fresh = social.recordInvite(Invite(UUID.randomUUID(), inviter, joiner.id, InviteKind.LEAGUE, league.code ?: "", joiner.createdAt.isAfter(now.minusSeconds(2 * 86_400)), now))
        if (fresh) progress?.record(inviter, com.arenacity.domain.rules.ProgressEvent.INVITE_JOINED)
        val owner = clubs.findById(inviter) ?: return
        inbox?.add(owner.id, "LEAGUE_JOIN", mapOf("leagueId" to league.id.toString(), "league" to league.name, "club" to joiner.name))
        push?.let {
            runCatching {
                it.sendLocalized(listOf(owner.userId), mapOf("type" to "LEAGUE_JOIN", "leagueId" to league.id.toString())) { lang ->
                    com.arenacity.common.i18n.ServerI18n.t("push.join_title", lang, league.name) to com.arenacity.common.i18n.ServerI18n.t("push.join_body", lang, joiner.name)
                }
            }
        }
    }
}
