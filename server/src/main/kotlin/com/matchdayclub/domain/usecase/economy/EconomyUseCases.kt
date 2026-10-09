package com.matchdayclub.domain.usecase.economy

import com.matchdayclub.common.time.Clock
import com.matchdayclub.common.tx.NoTx
import com.matchdayclub.common.tx.Tx
import com.matchdayclub.domain.exception.ConflictException
import com.matchdayclub.domain.exception.NotFoundException
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.*
import com.matchdayclub.domain.repository.AthleteRepository
import com.matchdayclub.domain.repository.BuildingRepository
import com.matchdayclub.domain.repository.ClubRepository
import com.matchdayclub.domain.repository.EconomyRepository
import com.matchdayclub.domain.rules.*
import com.matchdayclub.domain.usecase.club.ClubService
import java.time.Instant
import java.util.UUID

data class DailyClaim(val day: Int, val reward: Reward, val streak: Int, val club: Club)

class ClaimDailyRewardUseCase(private val service: ClubService, private val clubs: ClubRepository, private val economy: EconomyService, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID): DailyClaim = tx {
        val club = service.lockedClubOf(userId)
        val today = economy.today(club.timezone)
        if (club.loginLast == today) throw ConflictException("Already claimed today")
        val day = Economy.nextLoginDay(club.loginLast, club.loginDay, today)
        val reward = Economy.loginReward(day)
        val streak = if (club.loginLast != null && club.loginLast.plusDays(1) == today) club.streak + 1 else 1
        val updated = clubs.update(club.copy(coins = club.coins + reward.coins, gold = club.gold + reward.gold, loginDay = day, loginLast = today, streak = streak, updatedAt = clock.now()))
        economy.recordMax(club.id, Metric.BEST_STREAK, streak.toLong())
        DailyClaim(day, reward, streak, updated)
    }
}

data class PassView(val pass: SeasonPass, val level: Int, val tasks: List<DailyTask>, val seasonNumber: Int)

data class PassClaim(val reward: Reward, val items: List<String>, val elite: Athlete?, val view: PassView)

class SeasonPassUseCases(
    private val service: ClubService,
    private val clubs: ClubRepository,
    private val repo: EconomyRepository,
    private val economy: EconomyService,
    private val clock: Clock,
    private val tx: Tx = NoTx,
    private val cosmetics: CosmeticsService? = null,
    private val athletes: AthleteRepository? = null,
) {
    /** The pass follows the club's own season counter (leagues are re-formed every season, so their numbers restart). */
    private suspend fun passOf(club: Club): Pair<SeasonPass, Int> {
        val season = club.seasonNo
        val pass = repo.pass(club.id, season) ?: repo.upsertPass(SeasonPass(club.id, season, 0, false, 0, 0))
        return pass to season
    }

    suspend fun view(userId: UUID): PassView {
        val club = service.clubOf(userId)
        val (pass, season) = passOf(club)
        return PassView(pass, Economy.passLevel(pass.xp), economy.todaysTasks(club.id, club.timezone), season)
    }

    suspend fun claimTask(userId: UUID, task: TaskKind): PassView = tx {
        val club = service.lockedClubOf(userId)
        val t = economy.todaysTasks(club.id, club.timezone).firstOrNull { it.task == task.name } ?: throw NotFoundException("Task not active today")
        if (t.claimed) throw ConflictException("Already claimed")
        if (t.progress < task.target) throw ConflictException("Task not complete")
        repo.upsertTask(t.copy(claimed = true))
        val (pass, _) = passOf(club)
        repo.upsertPass(pass.copy(xp = pass.xp + task.xp))
        view(userId)
    }

    /**
     * Claims every reached, unclaimed level on the free track (and premium if owned): coins/gold, pass cosmetics
     * (gold instead when already owned from an earlier season) and, at the last premium level, an elite athlete
     * for [eliteSport] (one of the club's sections; football by default).
     */
    suspend fun claimLevels(userId: UUID, eliteSport: Sport = Sport.FOOTBALL): PassClaim = tx {
        val club = service.lockedClubOf(userId)
        val (pass, _) = passOf(club)
        val level = Economy.passLevel(pass.xp)
        if (level <= pass.claimedFree && (!pass.premium || level <= pass.claimedPremium)) throw ConflictException("Nothing to claim")
        var total = Reward()
        val items = mutableListOf<String>()
        var elite: Athlete? = null
        suspend fun item(l: Int, premium: Boolean) {
            val it = Economy.passItem(l, premium) ?: return
            val c = cosmetics ?: return
            if (c.grant(club.id, it, CosmeticSource.PASS)) items += it.id else total += Reward(gold = Cosmetics.DUPLICATE_GOLD)
        }
        for (l in pass.claimedFree + 1..level) { total += Economy.freeReward(l); item(l, false) }
        var claimedPremium = pass.claimedPremium
        if (pass.premium) {
            for (l in pass.claimedPremium + 1..level) {
                total += Economy.premiumReward(l); item(l, true)
                if (l == Economy.ELITE_LEVEL) elite = eliteAthlete(club, eliteSport)
            }
            claimedPremium = level
        }
        clubs.update(club.copy(coins = club.coins + total.coins, gold = club.gold + total.gold, updatedAt = clock.now()))
        repo.upsertPass(pass.copy(claimedFree = level, claimedPremium = claimedPremium))
        PassClaim(total, items, elite, view(userId))
    }

    private suspend fun eliteAthlete(club: Club, requested: Sport): Athlete? {
        val repo = athletes ?: return null
        val squad = repo.listByClub(club.id)
        val sport = if (squad.any { it.sport == requested }) requested else Sport.FOOTBALL
        val now = clock.now()
        val a = com.matchdayclub.domain.rules.AthleteFactory.create(now.toEpochMilli() xor club.id.mostSignificantBits xor 0x5EA5_0A11L, club.id, sport, Economy.ELITE_QUALITY_MIN, Economy.ELITE_QUALITY_MAX, null, now)
            .copy(rarity = Rarity.ELITE, age = 22)
        repo.createAll(listOf(a))
        return a
    }

    suspend fun unlockPremium(club: Club) { val (pass, _) = passOf(club); repo.upsertPass(pass.copy(premium = true)) }
}

/** Store receipt verification. Implementations must throw ValidationException on invalid receipts. */
fun interface ReceiptVerifier { suspend fun verify(productId: String, token: String): String /* external id */ }

class DevReceiptVerifier : ReceiptVerifier {
    override suspend fun verify(productId: String, token: String): String {
        if (!token.startsWith("dev:")) throw ValidationException("Invalid receipt")
        return "dev:$productId:${token.removePrefix("dev:")}"
    }
}

data class PurchaseResult(val status: String, val gold: Int, val coins: Long, val club: Club)

class VerifyPurchaseUseCase(private val service: ClubService, private val clubs: ClubRepository, private val repo: EconomyRepository, private val pass: SeasonPassUseCases, private val verifiers: Map<Store, ReceiptVerifier>, private val clock: Clock, private val tx: Tx = NoTx) {
    suspend operator fun invoke(userId: UUID, store: Store, productId: String, token: String): PurchaseResult {
        val product = Economy.PRODUCTS[productId] ?: throw ValidationException("Unknown product")
        val verifier = verifiers[store] ?: throw ValidationException("Store not supported")
        val externalId = verifier.verify(productId, token)   // network call to the store: outside the DB transaction
        return tx {
            val club = service.lockedClubOf(userId)
            if (repo.purchaseByExternalId(externalId) != null) PurchaseResult("DUPLICATE", 0, 0, club) else {
                val now = clock.now()
                repo.createPurchase(Purchase(UUID.randomUUID(), club.id, store.name, productId, externalId, "GRANTED", product.gold, product.coins, now))
                val updated = clubs.update(club.copy(gold = club.gold + product.gold, coins = club.coins + product.coins, updatedAt = now))
                if (product.premiumPass) pass.unlockPremium(updated)
                PurchaseResult("GRANTED", product.gold, product.coins, updated)
            }
        }
    }
}

enum class AdPlacement { DOUBLE_INCOME, FREE_SPEEDUP }

class AdRewardUseCase(private val service: ClubService, private val clubs: ClubRepository, private val buildings: BuildingRepository, private val economy: EconomyService, private val clock: Clock, private val tx: Tx = NoTx, private val repo: EconomyRepository? = null) {
    /** Client-reported reward: only enabled in development (production rewards come from [grantVerified]). */
    suspend operator fun invoke(userId: UUID, placement: AdPlacement, buildingId: UUID?): Club = tx { apply(service.lockedClubOf(userId), placement, buildingId) }

    /**
     * Reward confirmed by the ad network's server-side verification callback. Idempotent per [transactionId]
     * (callbacks are retried): returns null when that transaction was already granted.
     */
    suspend fun grantVerified(userId: UUID, placement: AdPlacement, buildingId: UUID?, transactionId: String): Club? = tx {
        val club = service.lockedClubOf(userId)
        val fresh = repo?.recordAdReward(transactionId, club.id, placement.name, clock.now()) ?: true
        if (fresh) apply(club, placement, buildingId) else null
    }

    private suspend fun apply(club: Club, placement: AdPlacement, buildingId: UUID?): Club {
        val now = clock.now()
        return when (placement) {
            AdPlacement.DOUBLE_INCOME -> {
                if (club.doubleIncomeAdAt != null && now.isBefore(club.doubleIncomeAdAt.plusSeconds(Economy.DOUBLE_INCOME_COOLDOWN_SECONDS))) throw ConflictException("Double income is on cooldown")
                clubs.update(club.copy(doubleIncomeUntil = now.plusSeconds(Economy.DOUBLE_INCOME_COOLDOWN_SECONDS), doubleIncomeAdAt = now, updatedAt = now))
            }
            AdPlacement.FREE_SPEEDUP -> {
                val today = economy.today(club.timezone)
                if (club.freeSpeedupDate == today) throw ConflictException("Free speed-up already used today")
                val b = service.building(club, buildingId ?: throw ValidationException("buildingId required"))
                if (b.state != BuildingState.UPGRADING || b.upgradeEndsAt == null) throw ConflictException("Nothing to speed up")
                if (b.upgradeEndsAt.epochSecond - now.epochSecond > Economy.FREE_SPEEDUP_MAX_SECONDS) throw ValidationException("Free speed-up only for timers under 30 minutes")
                service.complete(b, now)
                clubs.update(club.copy(freeSpeedupDate = today, updatedAt = now))
            }
        }
    }
}
