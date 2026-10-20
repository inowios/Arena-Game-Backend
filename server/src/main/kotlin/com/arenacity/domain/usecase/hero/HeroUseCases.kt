package com.arenacity.domain.usecase.hero

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.exception.ConflictException
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.Club
import com.arenacity.domain.model.ClubHero
import com.arenacity.domain.model.HeroLineup
import com.arenacity.domain.model.HeroSource
import com.arenacity.domain.model.HeroSpec
import com.arenacity.domain.repository.HeroRepository
import com.arenacity.domain.rules.HeroCatalog
import com.arenacity.domain.rules.MapSpots
import com.arenacity.domain.rules.HeroUnlock
import com.arenacity.domain.rules.Heroes
import com.arenacity.domain.rules.UnlockKind
import com.arenacity.domain.usecase.club.ClubInitializer
import com.arenacity.domain.usecase.club.ClubService
import com.arenacity.domain.usecase.club.ClubState
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * A catalog hero as one club sees it: [owned] = the club's row (null = still locked), [available] = owned, or it can be
 * had now (the stadium has reached the rule's level and the hero has an Arena kit), [canBuy] = locked, available and
 * enough gold to open it now, [reason] = why it is not available (rules.Heroes.unavailableReason: SOON for a hero
 * without a kit; null otherwise).
 */
data class HeroView(val spec: HeroSpec, val rule: HeroUnlock, val owned: ClubHero?, val available: Boolean, val canBuy: Boolean, val now: Instant? = null, val reason: String? = null) {
    /** Seconds left of the hero's training session (0 = done, ready to claim); null when it is not training. */
    val trainingLeft: Long? get() = owned?.trainingEndsAt?.let { end -> maxOf(0L, end.epochSecond - (now ?: end).epochSecond) }
}
data class HeroRoster(val heroes: List<HeroView>, val club: Club, val stadium: Int)
/** An unlock: the hero, the club after paying, and what it cost ([spent] 0 when the club already had the hero). */
data class HeroUnlockResult(val hero: HeroView, val club: Club, val spent: Int)
/** The Arena triple («Склад»): [saved] false = none saved yet (an empty list; the match then picks the best heroes itself). */
data class HeroLineupView(val heroes: List<String>, val saved: Boolean, val updatedAt: Instant?)
/**
 * Hero training in the training centre: [centerLevel] null = no working centre (nothing can train), then [coins] and
 * [xp] of a session at that level, [seconds] its length, [slots] heroes at once, [training] = the heroes in a session now.
 */
data class HeroTrainingStatus(val centerLevel: Int?, val slots: Int, val coins: Long, val xp: Int, val seconds: Long, val training: List<HeroView>)
/** A session started: the hero, the club after paying, the price, and the centre as it is now. */
data class HeroTrainResult(val hero: HeroView, val club: Club, val spent: Long, val status: HeroTrainingStatus)
/** A story hero given: [joined] = new in the club, else he was owned and got [xp] experience. */
data class HeroStoryGrant(val hero: HeroView, val joined: Boolean, val xp: Int)
/** A session claimed:the hero after the experience, how much it got ([gained] less than the session's at the level cap), the centre now. */
data class HeroClaimResult(val hero: HeroView, val gained: Int, val levelBefore: Int, val status: HeroTrainingStatus)

/**
 * Heroes instead of cards (docs/claude/HEROES-INSTEAD-OF-CARDS.md in the mobile repo; the rules in rules.Heroes): the
 * roster, opening a hero for gold, the Arena triple, training, and the experience heroes earn in Arena matches. Every club owns the start six: a new
 * one from [StartHeroesInitializer], an older one from its first access here ([ensureStart]).
 */
class HeroUseCases(private val service: ClubService, private val heroes: HeroRepository, private val clock: Clock, private val tx: Tx = NoTx) {
    /** The club's heroes with the start six added if any is missing (idempotent; a race only re-inserts nothing). */
    suspend fun ensureStart(clubId: UUID): List<ClubHero> {
        val have = heroes.listByClub(clubId)
        val missing = Heroes.START.filter { id -> have.none { it.heroId == id } }
        if (missing.isEmpty()) return have
        val now = clock.now()
        missing.forEach { heroes.insertIgnore(ClubHero(clubId, it, 1, 0, HeroSource.START, now)) }
        return heroes.listByClub(clubId)
    }

    private fun view(spec: HeroSpec, owned: ClubHero?, stadium: Int, gold: Int): HeroView {
        val rule = Heroes.rule(spec)
        val reason = if (owned == null) Heroes.unavailableReason(spec) else null
        val available = owned != null || (reason == null && Heroes.available(rule, stadium))
        return HeroView(spec, rule, owned, available, owned == null && reason == null && Heroes.canBuy(rule, stadium, gold), clock.now(), reason)
    }

    /** Every catalog hero, owned or locked, in catalog order. */
    suspend fun roster(userId: UUID): HeroRoster {
        val st = service.state(userId)
        val owned = ensureStart(st.club.id).associateBy { it.heroId }
        return HeroRoster(HeroCatalog.ALL.map { view(it, owned[it.id], st.stadium, st.club.gold) }, st.club, st.stadium)
    }

    /**
     * Opens [heroId] for gold: its people's island needs the stadium level, the price is taken in the same transaction
     * as the hero is added (the club row is locked). A hero the club already owns costs nothing and changes nothing.
     */
    suspend fun unlock(userId: UUID, heroId: String): HeroUnlockResult = tx {
        val spec = HeroCatalog.of(heroId) ?: throw NotFoundException("No such hero")
        val st = service.state(userId, lock = true)
        val have = ensureStart(st.club.id).firstOrNull { it.heroId == heroId }
        if (have != null) return@tx HeroUnlockResult(view(spec, have, st.stadium, st.club.gold), st.club, 0)
        val rule = Heroes.rule(spec)
        // the start six are owned by now (ensureStart); anything else is for sale from its stadium level
        if (rule.kind == UnlockKind.START) throw NotFoundException("No such hero")
        // the peoples' heroes have no Arena kit yet: not for sale until they get one (APP-AUDIT §2.9, 20 October 2026)
        if (Heroes.unavailableReason(spec) != null) throw ValidationException("Coming soon")
        if (!Heroes.available(rule, st.stadium)) throw ValidationException("Requires stadium level {0}", rule.stadium)
        val club = service.charge(st.club, gold = rule.gold)
        val hero = ClubHero(club.id, spec.id, 1, 0, HeroSource.CRYSTALS, clock.now())
        heroes.insertIgnore(hero)
        HeroUnlockResult(view(spec, hero, st.stadium, club.gold), club, rule.gold)
    }

    /**
     * Gives [xp] to each of the club's heroes in [heroIds] who played a match (ids the club does not own are skipped),
     * at most [Heroes.MAX_MATCH_XP_PER_DAY] per hero a UTC day. Runs inside the caller's transaction with the club locked.
     * Returns the heroes that played, as they are now.
     */
    suspend fun grantMatchXp(club: Club, heroIds: Collection<String>, xp: Int): List<HeroView> {
        if (heroIds.isEmpty()) return emptyList()
        val today = clock.now().atZone(ZoneOffset.UTC).toLocalDate()
        val owned = ensureStart(club.id).associateBy { it.heroId }
        return heroIds.distinct().mapNotNull { id ->
            val h = owned[id] ?: return@mapNotNull null
            val spec = HeroCatalog.of(id) ?: return@mapNotNull null
            val earnedToday = if (h.xpDay == today) h.xpToday else 0
            val gain = minOf(xp.coerceAtLeast(0), Heroes.MAX_MATCH_XP_PER_DAY - earnedToday).coerceAtLeast(0)
            val (level, rest) = Heroes.grow(h.level, h.xp, gain)
            val saved = heroes.update(h.copy(level = level, xp = rest, xpDay = today, xpToday = earnedToday + gain))
            view(spec, saved, stadium = 1, gold = 0)   // owned: stadium and gold do not matter
        }
    }

    /**
     * The story gives [heroId] (a chapter of «Іскра» claimed, usecase.story.StoryUseCases): a hero the club lacks joins at
     * level 1 with source STORY ([HeroStoryGrant.joined] true); one it already has (bought for gems before) gets
     * [ownedXp] experience instead. Runs inside the caller's transaction with the club locked.
     */
    suspend fun grantStory(club: Club, heroId: String, stadium: Int, ownedXp: Int): HeroStoryGrant {
        val spec = HeroCatalog.of(heroId) ?: throw NotFoundException("No such hero")
        val have = ensureStart(club.id).firstOrNull { it.heroId == heroId }
        if (have == null) {
            val hero = ClubHero(club.id, heroId, 1, 0, HeroSource.STORY, clock.now())
            heroes.insertIgnore(hero)
            return HeroStoryGrant(view(spec, hero, stadium, club.gold), true, 0)
        }
        val (level, rest) = Heroes.grow(have.level, have.xp, ownedXp)
        val gained = Heroes.totalXp(level, rest) - Heroes.totalXp(have.level, have.xp)
        val saved = heroes.update(have.copy(level = level, xp = rest))
        return HeroStoryGrant(view(spec, saved, stadium, club.gold), false, gained)
    }

    // ------------------------------------------------------------------ the Arena triple («Склад»)

    /** The saved triple, or an empty list with [HeroLineupView.saved] false. */
    suspend fun lineup(userId: UUID): HeroLineupView {
        val club = service.clubOf(userId)
        ensureStart(club.id)
        return heroes.lineup(club.id)?.let { HeroLineupView(it.heroes, true, it.updatedAt) } ?: HeroLineupView(emptyList(), false, null)
    }

    /** Saves the triple after rules.Heroes.lineupError (owned heroes with a kit only, no one twice, at most three). */
    suspend fun saveLineup(userId: UUID, picked: List<String>): HeroLineupView = tx {
        val club = service.lockedClubOf(userId)
        val owned = ensureStart(club.id).mapNotNull { h -> HeroCatalog.of(h.heroId)?.let { h.heroId to it } }.toMap()
        val ids = picked.map { it.trim() }.filter { it.isNotEmpty() }
        Heroes.lineupError(ids, owned)?.let { (template, arg) -> throw ValidationException(template, *listOfNotNull(arg).toTypedArray()) }
        val saved = heroes.saveLineup(HeroLineup(club.id, ids, clock.now()))
        HeroLineupView(saved.heroes, true, saved.updatedAt)
    }

    // ------------------------------------------------------------------ training in the training centre

    /** The level heroes train at: the stadium level (the five islands map, 17 October 2026: the training centre went
     *  with the city builder; rules.MapSpots keeps its slots and gain by the stadium level). Every club trains. */
    private fun centerLevel(st: ClubState): Int = st.stadium.coerceIn(1, MapSpots.MAX_STADIUM)

    private fun status(st: ClubState, owned: List<ClubHero>): HeroTrainingStatus {
        val level = centerLevel(st)
        val busy = owned.filter { it.training }.mapNotNull { h -> HeroCatalog.of(h.heroId)?.let { view(it, h, 1, 0) } }
        return HeroTrainingStatus(level, level?.let(Heroes::trainingSlots) ?: 0, level?.let(Heroes::trainingCoins) ?: 0, level?.let(Heroes::trainingXp) ?: 0,
            Heroes.TRAINING_SECONDS, busy)
    }

    suspend fun trainingStatus(userId: UUID): HeroTrainingStatus {
        val st = service.state(userId)
        return status(st, ensureStart(st.club.id))
    }

    /**
     * Starts a session for [heroId]: the club needs a working training centre, a free slot (heroes at once = its slots),
     * a hero it owns that is not training and below the top level, and the coins of the centre's level. The experience
     * is fixed now (the centre's level today) and given by [claimTraining] after [Heroes.TRAINING_SECONDS].
     */
    suspend fun train(userId: UUID, heroId: String): HeroTrainResult = tx {
        val spec = HeroCatalog.of(heroId) ?: throw NotFoundException("No such hero")
        val st = service.state(userId, lock = true)
        val level = centerLevel(st)
        val owned = ensureStart(st.club.id)
        val hero = owned.firstOrNull { it.heroId == heroId } ?: throw ValidationException("Hero {0} is not in your club", heroId)
        if (hero.training) throw ConflictException("The hero is already training")
        if (hero.level >= Heroes.MAX_LEVEL) throw ValidationException("The hero is at the top level")
        if (owned.count { it.training } >= Heroes.trainingSlots(level)) throw ConflictException("All training slots are busy")
        val price = Heroes.trainingCoins(level)
        val club = service.charge(st.club, coins = price)
        val now = clock.now()
        val saved = heroes.update(hero.copy(trainingEndsAt = now.plusSeconds(Heroes.TRAINING_SECONDS), trainingXp = Heroes.trainingXp(level)))
        HeroTrainResult(view(spec, saved, st.stadium, club.gold), club, price, status(st.copy(club = club), owned.map { if (it.heroId == heroId) saved else it }))
    }

    /**
     * Claims a finished session: the hero grows by its experience up to level 10 (the rest is dropped; the match day
     * cap does not count training). 409 while the timer runs or when the hero is not training.
     */
    suspend fun claimTraining(userId: UUID, heroId: String): HeroClaimResult = tx {
        val spec = HeroCatalog.of(heroId) ?: throw NotFoundException("No such hero")
        val st = service.state(userId, lock = true)
        val owned = ensureStart(st.club.id)
        val hero = owned.firstOrNull { it.heroId == heroId } ?: throw ValidationException("Hero {0} is not in your club", heroId)
        val ends = hero.trainingEndsAt ?: throw ConflictException("The hero is not training")
        if (clock.now().isBefore(ends)) throw ConflictException("Training still in progress")
        val (level, rest) = Heroes.grow(hero.level, hero.xp, hero.trainingXp)
        val gained = Heroes.totalXp(level, rest) - Heroes.totalXp(hero.level, hero.xp)
        val saved = heroes.update(hero.copy(level = level, xp = rest, trainingEndsAt = null, trainingXp = 0))
        HeroClaimResult(view(spec, saved, st.stadium, st.club.gold), gained, hero.level, status(st, owned.map { if (it.heroId == heroId) saved else it }))
    }
}

/** Gives a new player club the start six (bots have no heroes). */
class StartHeroesInitializer(private val heroes: HeroUseCases) : ClubInitializer {
    override suspend fun onClubCreated(club: Club) { if (!club.isBot) heroes.ensureStart(club.id) }
}
