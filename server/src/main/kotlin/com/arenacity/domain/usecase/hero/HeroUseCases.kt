package com.arenacity.domain.usecase.hero

import com.arenacity.common.time.Clock
import com.arenacity.common.tx.NoTx
import com.arenacity.common.tx.Tx
import com.arenacity.domain.exception.NotFoundException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.model.Club
import com.arenacity.domain.model.ClubHero
import com.arenacity.domain.model.HeroSource
import com.arenacity.domain.model.HeroSpec
import com.arenacity.domain.repository.HeroRepository
import com.arenacity.domain.rules.HeroCatalog
import com.arenacity.domain.rules.HeroUnlock
import com.arenacity.domain.rules.Heroes
import com.arenacity.domain.rules.UnlockKind
import com.arenacity.domain.usecase.club.ClubInitializer
import com.arenacity.domain.usecase.club.ClubService
import java.time.ZoneOffset
import java.util.UUID

/**
 * A catalog hero as one club sees it: [owned] = the club's row (null = still locked), [available] = owned, or the
 * stadium has reached the rule's level, [canBuy] = locked, available and enough gold to open it now.
 */
data class HeroView(val spec: HeroSpec, val rule: HeroUnlock, val owned: ClubHero?, val available: Boolean, val canBuy: Boolean)
data class HeroRoster(val heroes: List<HeroView>, val club: Club, val stadium: Int)
/** An unlock: the hero, the club after paying, and what it cost ([spent] 0 when the club already had the hero). */
data class HeroUnlockResult(val hero: HeroView, val club: Club, val spent: Int)

/**
 * Heroes instead of cards (docs/claude/HEROES-INSTEAD-OF-CARDS.md in the mobile repo; the rules in rules.Heroes): the
 * roster, opening a hero for gold, and the experience heroes earn in card matches. Every club owns the start six: a new
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
        return HeroView(spec, rule, owned, owned != null || Heroes.available(rule, stadium), owned == null && Heroes.canBuy(rule, stadium, gold))
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
}

/** Gives a new player club the start six (bots have no heroes). */
class StartHeroesInitializer(private val heroes: HeroUseCases) : ClubInitializer {
    override suspend fun onClubCreated(club: Club) { if (!club.isBot) heroes.ensureStart(club.id) }
}
