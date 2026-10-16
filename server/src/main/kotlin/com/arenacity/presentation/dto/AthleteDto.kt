package com.arenacity.presentation.dto

import com.arenacity.domain.model.Athlete
import com.arenacity.domain.model.Lineup
import com.arenacity.domain.model.Training
import com.arenacity.engine.model.Tactics
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable data class AthleteDto(
    val id: String, val name: String, val sport: String, val position: String, val overall: Int,
    val attack: Int, val defense: Int, val physical: Int, val technique: Int, val mental: Int,
    val age: Int, val rarity: String, val fatigue: Int, val injured: Boolean, val injuredUntil: String?, val morale: Int,
    val potential: Int = 60,
    val xp: Int = 0, val xpNext: Int = 100,
    /** Market value in coins (selling returns half). */
    val value: Long = 0,
    /** Technique levels by key (SHOOT, PASS, CROSS, FEINT, TACKLE, SLIDE, BLOCK, JUMP → 1..5) and points to spend on them. */
    val techniques: Map<String, Int> = emptyMap(),
    val skillPoints: Int = 0,
    /** The move he was born for (its steps cost a point less), if any. */
    val talent: String? = null,
    /** The card kind (CLASSIC … ARENA), the career experience and the card level it reaches within the kind's ceiling. */
    val cardKind: String = "CLASSIC", val xpTotal: Int = 0, val cardLevel: Int = 1,
)
@Serializable data class TrainingDto(val id: String, val athleteId: String, val attribute: String, val delta: Int, val endsAt: String, val remainingSeconds: Long)
@Serializable data class SquadResponse(val athletes: List<AthleteDto>, val trainings: List<TrainingDto>, val trainingSlots: Int, val serverTime: String)
@Serializable data class StartTrainingRequest(val athleteId: String, val attribute: String)
@Serializable data class AcademyClaimRequest(val sport: String? = null)
@Serializable data class LineupDto(val sport: String, val starters: List<String>, val bench: List<String>, val tactics: Tactics, val isDefault: Boolean)
@Serializable data class LineupResponse(val lineup: LineupDto, val athletes: List<AthleteDto>)
@Serializable data class SetLineupRequest(val starters: List<String>, val bench: List<String> = emptyList(), val tactics: Tactics = Tactics())

fun Athlete.toDto(now: Instant) = AthleteDto(id.toString(), name, sport.name, position.name, overall, attack, defense, physical, technique, mental, age, rarity.name, currentFatigue(now), isInjured(now), injuredUntil?.toString(), morale, potential, xp, com.arenacity.domain.rules.Growth.XP_PER_POINT, com.arenacity.domain.rules.Market.price(this), techniques, skillPoints, talent, cardKind.name, xpTotal, com.arenacity.domain.model.CardKind.levelFor(xpTotal, cardKind))
@Serializable data class UpgradeTechniqueResponse(val athlete: AthleteDto, val level: Int, val cost: Int)
fun Training.toDto(now: Instant) = TrainingDto(id.toString(), athleteId.toString(), kindKey, delta, endsAt.toString(), maxOf(0, endsAt.epochSecond - now.epochSecond))
fun Lineup.toDto() = LineupDto(sport.name, starters.map { it.toString() }, bench.map { it.toString() }, tactics, isDefault)

@Serializable data class ScoutOfferDto(val slot: Int, val kind: String, val athlete: AthleteDto)
@Serializable data class ScoutReportDto(val offers: List<ScoutOfferDto>, val signedToday: Boolean, val nextAt: String)

@Serializable data class PackOfferDto(val kind: String, val coins: Long, val gold: Int, val chances: List<Double>, val unlockLevel: Int, val open: Boolean, val freeToday: Boolean, val cards: Int = com.arenacity.domain.rules.Packs.CARDS_PER_PACK)
@Serializable data class PackShelfDto(val packs: List<PackOfferDto>, val sinceEpic: Int, val sinceLegend: Int, val epicPity: Int, val legendPity: Int, val collection: Int, val collectionMax: Int)
@Serializable data class PackOpenResponse(val kind: String, val tiers: List<String>, val athletes: List<AthleteDto>, val club: ClubDto,
                                         /** Set when the opening was the day's offer (POST /club/offer/buy): the offer, now bought. */
                                         val offer: DailyOfferDto? = null)
/**
 * The day's pack offer (rules.DailyOffers): the pack [packId] (a pack kind) for [price] gems instead of [oldPrice]
 * ([discountPercent] off) until [endsAt] (the next UTC midnight); [open] = the club's level has opened that pack
 * ([unlockLevel]), [bought] = the club bought it today (once a day).
 */
@Serializable data class DailyOfferDto(val packId: String, val oldPrice: Int, val price: Int, val discountPercent: Int, val endsAt: String, val open: Boolean, val bought: Boolean, val unlockLevel: Int)
/** A card match started (POST /club/cards/start): the energy left. */
@Serializable data class CardStartResponse(val energy: EnergyDto)
fun com.arenacity.domain.usecase.athlete.DailyOfferView.toDto() = DailyOfferDto(offer.spec.kind.name, offer.oldPrice, offer.price, offer.discountPercent, offer.endsAt.toString(), open, bought, offer.spec.unlockLevel)

fun com.arenacity.domain.usecase.athlete.PackShelf.toDto() = PackShelfDto(
    offers.map { PackOfferDto(it.spec.kind.name, it.spec.coins, it.spec.gold, it.spec.chances, it.spec.unlockLevel, it.open, it.freeToday) },
    sinceEpic, sinceLegend, com.arenacity.domain.rules.Packs.EPIC_PITY, com.arenacity.domain.rules.Packs.LEGEND_PITY, collection, com.arenacity.domain.rules.Packs.COLLECTION_MAX)
fun com.arenacity.domain.usecase.athlete.PackResult.toDto(now: Instant) = PackOpenResponse(open.kind.name, open.tiers.map { it.name }, athletes.map { it.toDto(now) }, club.toDto())

@Serializable data class CardXpDto(val id: String, val xp: Int)
/** [heroes] = the ids of the club's heroes who played ("p001"…), at most 10; the server sets their experience by the result. */
@Serializable data class CardMatchResultRequest(val seed: Int, val won: Boolean, val draw: Boolean = false, val goalsFor: Int = 0, val goalsAgainst: Int = 0, val cards: List<CardXpDto> = emptyList(), val stake: Int = 1, val conceded: Boolean = false,
                                                val heroes: List<String> = emptyList())
@Serializable data class CardOpponentResponse(val club: ClubRefDto, val athletes: List<AthleteDto>)
/** [heroes] = the heroes who played as they are now, [heroXp] = the experience each was given (less once a hero hits the daily cap). */
@Serializable data class CardMatchResultResponse(val coins: Long, val club: ClubDto, val athletes: List<AthleteDto>, val heroes: List<HeroDto> = emptyList(), val heroXp: Int = 0)
