package com.arenacity.domain.usecase.alliance

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.exception.*
import com.arenacity.domain.model.Club
import com.arenacity.domain.repository.ClubRepository
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.rules.ProgressEvent
import com.arenacity.domain.usecase.economy.ProgressTracker
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// ---------------------------------------------------------------------------------------------------------------------
// Alliances (GAME-PLAN phase 4): clubs band together — chat, roles, a weekend tournament of the members' arena wins,
// likes on visits. (The help on constructions went with the city builder, 17 October 2026; helpsGiven is what a member gave before.)

enum class AllianceRole(val rank: Int) { LEADER(4), CO_LEADER(3), ELDER(2), MEMBER(1) }

data class Alliance(
    val id: UUID, val name: String, val tag: String, val description: String, val emblemJson: String?,
    val open: Boolean, val minTrophies: Int, val createdBy: UUID, val createdAt: Instant, val updatedAt: Instant,
)
data class AllianceMember(val allianceId: UUID, val clubId: UUID, val role: AllianceRole, val joinedAt: Instant, val helpsGiven: Int = 0)
data class JoinRequest(val allianceId: UUID, val clubId: UUID, val message: String, val createdAt: Instant)
interface AllianceRepository {
    suspend fun create(a: Alliance): Alliance
    suspend fun update(a: Alliance): Alliance
    suspend fun delete(id: UUID)
    suspend fun find(id: UUID): Alliance?
    suspend fun findByName(name: String): Alliance?
    suspend fun search(query: String?, limit: Int): List<Alliance>
    suspend fun memberCounts(ids: Collection<UUID>): Map<UUID, Int>

    suspend fun members(allianceId: UUID): List<AllianceMember>
    suspend fun membership(clubId: UUID): AllianceMember?
    suspend fun addMember(m: AllianceMember): Boolean
    suspend fun updateMember(m: AllianceMember)
    suspend fun removeMember(allianceId: UUID, clubId: UUID)

    suspend fun requests(allianceId: UUID): List<JoinRequest>
    suspend fun addRequest(r: JoinRequest): Boolean
    suspend fun removeRequest(allianceId: UUID, clubId: UUID)
    suspend fun clearRequests(clubId: UUID)

    suspend fun addWeekendWin(week: Int, allianceId: UUID, clubId: UUID)
    /** Alliances by total weekend wins, best first. */
    suspend fun weekendTable(week: Int, limit: Int): List<Pair<UUID, Int>>
    suspend fun weekendWins(week: Int, allianceId: UUID): Map<UUID, Int>
    suspend fun claimWeekend(week: Int, clubId: UUID, at: Instant): Boolean

    suspend fun like(clubId: UUID, liker: UUID, week: Int, at: Instant): Boolean
    suspend fun likes(clubId: UUID, week: Int): Int
    suspend fun liked(clubId: UUID, liker: UUID, week: Int): Boolean
    suspend fun topLiked(week: Int, limit: Int): List<Pair<UUID, Int>>
}

object AllianceRules {
    const val MAX_MEMBERS = 30
    const val CREATE_COINS = 1_000L
    const val CREATE_STADIUM = 2
    const val NAME = 3
    const val NAME_MAX = 24

    /** Weekend reward of a member who won at least [MIN_WINS] arena matches, by the alliance's place. */
    const val MIN_WINS = 3
    fun weekendGold(place: Int) = when { place == 1 -> 120; place == 2 -> 80; place == 3 -> 60; place <= 10 -> 30; place <= 50 -> 15; else -> 5 }
    const val WIN_COINS = 200L

    /** ISO week key (year * 100 + week) of the weekend [now] falls in (Saturday 00:00 – Monday 00:00 UTC), null on weekdays. */
    fun weekendNow(now: Instant): Int? {
        val d = now.atZone(ZoneOffset.UTC)
        return if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) week(now) else null
    }
    fun week(now: Instant): Int { val d = now.atZone(ZoneOffset.UTC).toLocalDate(); val wf = java.time.temporal.WeekFields.ISO; return d.get(wf.weekBasedYear()) * 100 + d.get(wf.weekOfWeekBasedYear()) }
    /** The last weekend that is over (the one whose rewards can be claimed now). */
    fun lastWeekend(now: Instant): Int = if (weekendNow(now) != null) week(now.minusSeconds(7 * 86_400)) else week(now.minusSeconds(now.atZone(ZoneOffset.UTC).dayOfWeek.value * 86_400L))
}

/** [now]: when it was read (the members' online state, club.Presence). */
data class AllianceView(val alliance: Alliance, val members: List<Pair<AllianceMember, Club>>, val requests: List<Pair<JoinRequest, Club>>, val me: AllianceMember?, val now: java.time.Instant = java.time.Instant.EPOCH)
data class AllianceSummary(val alliance: Alliance, val members: Int)
data class TournamentRow(val alliance: Alliance, val wins: Int, val place: Int)
data class TournamentView(val week: Int, val live: Boolean, val rows: List<TournamentRow>, val mine: TournamentRow?, val myWins: Int, val claimable: WeekendReward?)
data class WeekendReward(val week: Int, val place: Int, val wins: Int, val gold: Int, val coins: Long)

class AllianceUseCases(
    private val service: ClubService,
    private val clubs: ClubRepository,
    private val repo: AllianceRepository,
    private val clock: Clock,
    private val tx: Tx = NoTx,
) {
    private val tagRe = Regex("^[A-Z0-9]{2,5}$")

    private suspend fun myMembership(club: Club) = repo.membership(club.id) ?: throw NotFoundException("You are not in an alliance")
    private suspend fun alliance(id: UUID) = repo.find(id) ?: throw NotFoundException("Alliance not found")
    private fun requireRole(m: AllianceMember, at: AllianceRole) { if (m.role.rank < at.rank) throw AuthorizationException("Not allowed for your role") }

    suspend fun search(query: String?): List<AllianceSummary> {
        val list = repo.search(query?.trim()?.takeIf { it.isNotEmpty() }, 30)
        val counts = repo.memberCounts(list.map { it.id })
        return list.map { AllianceSummary(it, counts[it.id] ?: 0) }
    }

    suspend fun create(userId: UUID, name: String, tag: String, description: String, open: Boolean, minTrophies: Int, emblemJson: String?): AllianceView = tx {
        val st = service.state(userId, lock = true); val club = st.club
        if (repo.membership(club.id) != null) throw ConflictException("Leave your alliance first")
        if (st.stadium < AllianceRules.CREATE_STADIUM) throw ValidationException("Requires stadium level {0}", AllianceRules.CREATE_STADIUM)
        val n = name.trim()
        if (n.length !in AllianceRules.NAME..AllianceRules.NAME_MAX) throw ValidationException("Alliance name must be 3..24 characters")
        val t = tag.trim().uppercase()
        if (!tagRe.matches(t)) throw ValidationException("Tag must be 2..5 letters or digits")
        if (repo.findByName(n) != null) throw ConflictException("That name is taken")
        if (emblemJson != null && emblemJson.length > 2048) throw ValidationException("Emblem too large")
        service.charge(club, coins = AllianceRules.CREATE_COINS)
        val now = clock.now()
        val a = repo.create(Alliance(UUID.randomUUID(), n, t, description.trim().take(160), emblemJson, open, minTrophies.coerceIn(0, 10_000), club.id, now, now))
        repo.addMember(AllianceMember(a.id, club.id, AllianceRole.LEADER, now))
        repo.clearRequests(club.id)
        view(a, club)
    }

    suspend fun mine(userId: UUID): AllianceView? {
        val club = service.clubOf(userId)
        val m = repo.membership(club.id) ?: return null
        return view(alliance(m.allianceId), club)
    }

    suspend fun details(userId: UUID, id: UUID): AllianceView = view(alliance(id), service.clubOf(userId))

    private suspend fun view(a: Alliance, me: Club): AllianceView {
        val members = repo.members(a.id)
        val byId = clubs.findByIds(members.map { it.clubId }.toSet()).associateBy { it.id }
        val mine = members.firstOrNull { it.clubId == me.id }
        val reqs = if (mine != null && mine.role.rank >= AllianceRole.CO_LEADER.rank) repo.requests(a.id) else emptyList()
        val reqClubs = clubs.findByIds(reqs.map { it.clubId }.toSet()).associateBy { it.id }
        val now = clock.now()
        return AllianceView(a, members.sortedWith(compareByDescending<AllianceMember> { it.role.rank }.thenBy { byId[it.clubId]?.trophies?.unaryMinus() ?: 0 }).mapNotNull { m -> byId[m.clubId]?.let { m to it } },
            reqs.mapNotNull { r -> reqClubs[r.clubId]?.let { r to it } }, mine, now)
    }

    /** Joins an open alliance (or asks to join a closed one; returns null then). */
    suspend fun join(userId: UUID, id: UUID, message: String = ""): AllianceView? = tx {
        val club = service.lockedClubOf(userId)
        if (repo.membership(club.id) != null) throw ConflictException("Leave your alliance first")
        val a = alliance(id)
        if (club.trophies < a.minTrophies) throw ValidationException("Requires {0} trophies", a.minTrophies)
        if (repo.members(a.id).size >= AllianceRules.MAX_MEMBERS) throw ConflictException("The alliance is full")
        val now = clock.now()
        if (!a.open) { repo.addRequest(JoinRequest(a.id, club.id, message.trim().take(120), now)); return@tx null }
        repo.addMember(AllianceMember(a.id, club.id, AllianceRole.MEMBER, now))
        repo.clearRequests(club.id)
        view(a, club)
    }

    suspend fun leave(userId: UUID) = tx {
        val club = service.lockedClubOf(userId)
        val m = myMembership(club)
        repo.removeMember(m.allianceId, club.id)
        val rest = repo.members(m.allianceId)
        if (rest.isEmpty()) { repo.delete(m.allianceId); return@tx }
        if (m.role == AllianceRole.LEADER) {
            // the most senior member takes over
            val next = rest.sortedWith(compareByDescending<AllianceMember> { it.role.rank }.thenBy { it.joinedAt }).first()
            repo.updateMember(next.copy(role = AllianceRole.LEADER))
        }
    }

    suspend fun update(userId: UUID, description: String?, open: Boolean?, minTrophies: Int?, emblemJson: String?): AllianceView = tx {
        val club = service.lockedClubOf(userId)
        val m = myMembership(club); requireRole(m, AllianceRole.CO_LEADER)
        if (emblemJson != null && emblemJson.length > 2048) throw ValidationException("Emblem too large")
        val a = alliance(m.allianceId)
        val u = repo.update(a.copy(description = description?.trim()?.take(160) ?: a.description, open = open ?: a.open, minTrophies = minTrophies?.coerceIn(0, 10_000) ?: a.minTrophies,
            emblemJson = emblemJson ?: a.emblemJson, updatedAt = clock.now()))
        view(u, club)
    }

    suspend fun answer(userId: UUID, clubId: UUID, accept: Boolean): AllianceView = tx {
        val club = service.lockedClubOf(userId)
        val m = myMembership(club); requireRole(m, AllianceRole.CO_LEADER)
        val req = repo.requests(m.allianceId).firstOrNull { it.clubId == clubId } ?: throw NotFoundException("Request not found")
        repo.removeRequest(m.allianceId, clubId)
        if (accept) {
            if (repo.membership(clubId) != null) throw ConflictException("That club is already in an alliance")
            if (repo.members(m.allianceId).size >= AllianceRules.MAX_MEMBERS) throw ConflictException("The alliance is full")
            repo.addMember(AllianceMember(m.allianceId, req.clubId, AllianceRole.MEMBER, clock.now()))
            repo.clearRequests(req.clubId)
        }
        view(alliance(m.allianceId), club)
    }

    /** promote / demote / kick: leaders over everyone, co-leaders over elders and members. */
    suspend fun manage(userId: UUID, clubId: UUID, action: String): AllianceView = tx {
        val club = service.lockedClubOf(userId)
        val me = myMembership(club); requireRole(me, AllianceRole.CO_LEADER)
        val target = repo.members(me.allianceId).firstOrNull { it.clubId == clubId } ?: throw NotFoundException("Member not found")
        if (target.clubId == club.id || target.role.rank >= me.role.rank) throw AuthorizationException("Not allowed for your role")
        when (action) {
            "promote" -> {
                val next = AllianceRole.values().first { it.rank == target.role.rank + 1 }
                if (next == AllianceRole.LEADER) {
                    // handing over the alliance: the leader steps down to co-leader
                    if (me.role != AllianceRole.LEADER) throw AuthorizationException("Not allowed for your role")
                    repo.updateMember(me.copy(role = AllianceRole.CO_LEADER))
                } else if (next.rank >= me.role.rank) throw AuthorizationException("Not allowed for your role")
                repo.updateMember(target.copy(role = next))
            }
            "demote" -> { if (target.role == AllianceRole.MEMBER) throw ValidationException("Already a member"); repo.updateMember(target.copy(role = AllianceRole.values().first { it.rank == target.role.rank - 1 })) }
            "kick" -> repo.removeMember(me.allianceId, target.clubId)
            else -> throw ValidationException("Unknown action")
        }
        view(alliance(me.allianceId), club)
    }

    // ---- weekend tournament ----

    /** Fed from the progress events: an arena win on a weekend counts for the club's alliance. */
    suspend fun recordWin(clubId: UUID) {
        val week = AllianceRules.weekendNow(clock.now()) ?: return
        val m = repo.membership(clubId) ?: return
        repo.addWeekendWin(week, m.allianceId, clubId)
    }

    suspend fun tournament(userId: UUID): TournamentView {
        val club = service.clubOf(userId)
        val now = clock.now()
        val live = AllianceRules.weekendNow(now)
        val week = live ?: AllianceRules.lastWeekend(now)
        val table = repo.weekendTable(week, 50)
        val byId = table.mapNotNull { (id, _) -> repo.find(id) }.associateBy { it.id }
        val rows = table.mapIndexedNotNull { i, (id, wins) -> byId[id]?.let { TournamentRow(it, wins, i + 1) } }
        val m = repo.membership(club.id)
        val mine = m?.let { mm -> rows.firstOrNull { it.alliance.id == mm.allianceId } ?: repo.find(mm.allianceId)?.let { TournamentRow(it, repo.weekendWins(week, it.id).values.sum(), 0) } }
        val myWins = m?.let { repo.weekendWins(week, it.allianceId)[club.id] } ?: 0
        val claimable = if (live == null && mine != null && mine.place > 0 && myWins >= AllianceRules.MIN_WINS) WeekendReward(week, mine.place, myWins, AllianceRules.weekendGold(mine.place), myWins * AllianceRules.WIN_COINS) else null
        return TournamentView(week, live != null, rows, mine, myWins, claimable)
    }

    suspend fun claimWeekend(userId: UUID): WeekendReward = tx {
        val view = tournament(userId)
        val r = view.claimable ?: throw ConflictException("Nothing to claim")
        val club = service.lockedClubOf(userId)
        if (!repo.claimWeekend(r.week, club.id, clock.now())) throw ConflictException("Already claimed")
        clubs.update(club.copy(gold = club.gold + r.gold, coins = club.coins + r.coins, updatedAt = clock.now()))
        r
    }

    // ---- visits 2.0: likes ----

    suspend fun like(userId: UUID, clubId: UUID): Int = tx {
        val me = service.clubOf(userId)
        if (me.id == clubId) throw ValidationException("You cannot like your own club")
        clubs.findById(clubId) ?: throw NotFoundException("Club not found")
        val week = AllianceRules.week(clock.now())
        if (!repo.like(clubId, me.id, week, clock.now())) throw ConflictException("Already liked this week")
        repo.likes(clubId, week)
    }

    data class LikeState(val likes: Int, val likedByMe: Boolean, val alliance: Alliance?)
    suspend fun likeState(userId: UUID, clubId: UUID): LikeState {
        val me = service.clubOf(userId); val week = AllianceRules.week(clock.now())
        return LikeState(repo.likes(clubId, week), repo.liked(clubId, me.id, week), repo.membership(clubId)?.let { repo.find(it.allianceId) })
    }

    /** The most-liked clubs of the week ("the most beautiful club"). */
    suspend fun topLiked(): List<Pair<Club, Int>> {
        val top = repo.topLiked(AllianceRules.week(clock.now()), 20)
        val byId = clubs.findByIds(top.map { it.first }.toSet()).associateBy { it.id }
        return top.mapNotNull { (id, n) -> byId[id]?.let { it to n } }
    }

    /** Whether [clubId] belongs to the alliance [allianceId] (the alliance chat channel). */
    suspend fun isMember(clubId: UUID, allianceId: UUID) = repo.membership(clubId)?.allianceId == allianceId
}

/** Forwards arena wins to the alliance tournament (wired next to the economy's own progress tracking). */
class AllianceProgress(private val alliances: () -> AllianceUseCases) : ProgressTracker {
    override suspend fun record(clubId: UUID, event: ProgressEvent) {
        if (event == ProgressEvent.ARENA_WON) runCatching { alliances().recordWin(clubId) }
    }
}
