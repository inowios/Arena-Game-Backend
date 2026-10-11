package com.matchdayclub.presentation.route

import com.matchdayclub.common.response.toSuccessResponse
import com.matchdayclub.common.time.Clock
import com.matchdayclub.core.plugins.AUTH_JWT
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.model.AttributeKind
import com.matchdayclub.domain.model.Sport
import com.matchdayclub.domain.usecase.athlete.*
import com.matchdayclub.presentation.dto.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import java.util.UUID

private fun RoutingCall.uuid(name: String) = runCatching { UUID.fromString(parameters[name]) }.getOrElse { throw ValidationException("Bad $name") }
private inline fun <reified E : Enum<E>> parseEnum(v: String, what: String): E = runCatching { enumValueOf<E>(v.uppercase()) }.getOrElse { throw ValidationException("Unknown $what '$v'") }

fun Route.athleteRoutes() {
    val list: ListAthletesUseCase by inject()
    val claim: ClaimAcademyUseCase by inject()
    val start: StartTrainingUseCase by inject()
    val complete: CompleteTrainingUseCase by inject()
    val heal: HealAthleteUseCase by inject()
    val speedUpTraining: SpeedUpTrainingUseCase by inject()
    val speedUpMedical: SpeedUpMedicalUseCase by inject()
    val release: ReleaseAthleteUseCase by inject()
    val upgradeTechnique: UpgradeTechniqueUseCase by inject()
    val history: com.matchdayclub.domain.usecase.live.AthleteMatchHistoryUseCase by inject()
    val scouting: ScoutingUseCases by inject()
    val getLineup: GetLineupUseCase by inject()
    val setLineup: SetLineupUseCase by inject()
    val packs: PackUseCases by inject()
    val cardMatch: CardMatchUseCases by inject()
    val clock: Clock by inject()

    authenticate(AUTH_JWT) {
        route("/club") {
            get("/athletes") {
                val s = list(call.userId()); val now = clock.now()
                call.respond(SquadResponse(s.athletes.map { it.toDto(now) }, s.trainings.map { it.toDto(now) }, s.slots, now.toString()).toSuccessResponse())
            }
            get("/packs") { call.respond(packs.shelf(call.userId()).toDto().toSuccessResponse()) }
            post("/packs/{kind}/open") {
                val kind = parseEnum<com.matchdayclub.domain.model.CardKind>(call.parameters["kind"] ?: "", "pack")
                call.respond(HttpStatusCode.Created, packs.open(call.userId(), kind).toDto(clock.now()).toSuccessResponse(201))
            }
            get("/cards/opponent") {
                val o = cardMatch.opponent(call.userId()) ?: throw com.matchdayclub.domain.exception.NotFoundException("No opponent around")
                val now = clock.now()
                call.respond(CardOpponentResponse(o.club.toRef(), o.athletes.map { it.toDto(now) }).toSuccessResponse())
            }
            post("/cards/result") {
                val r = call.receive<CardMatchResultRequest>()
                val cards = r.cards.associate { (runCatching { UUID.fromString(it.id) }.getOrElse { throw ValidationException("Bad athlete id") }) to it.xp }
                val o = cardMatch.report(call.userId(), com.matchdayclub.domain.usecase.athlete.CardMatchReport(r.seed, r.won, r.draw, r.goalsFor, r.goalsAgainst, cards, r.stake, r.conceded))
                val now = clock.now()
                call.respond(CardMatchResultResponse(o.coins, o.club.toDto(), o.athletes.map { it.toDto(now) }).toSuccessResponse())
            }
            post("/academy/claim") {
                val sport = runCatching { call.receive<AcademyClaimRequest>() }.getOrNull()?.sport?.let { parseEnum<Sport>(it, "sport") } ?: Sport.FOOTBALL
                call.respond(HttpStatusCode.Created, claim(call.userId(), sport).toDto(clock.now()).toSuccessResponse(201))
            }
            post("/trainings") {
                val r = call.receive<StartTrainingRequest>()
                val kind = if (r.attribute.equals("SKILL", ignoreCase = true)) null else parseEnum<AttributeKind>(r.attribute, "attribute")
                val t = start(call.userId(), runCatching { UUID.fromString(r.athleteId) }.getOrElse { throw ValidationException("Bad athleteId") }, kind)
                call.respond(HttpStatusCode.Created, t.toDto(clock.now()).toSuccessResponse(201))
            }
            post("/trainings/{id}/complete") { call.respond(complete(call.userId(), call.uuid("id")).toDto(clock.now()).toSuccessResponse()) }
            post("/trainings/{id}/speedup") {
                val (athlete, club) = speedUpTraining(call.userId(), call.uuid("id"))
                call.respond(SpeedUpTrainingResponse(athlete.toDto(clock.now()), club.toDto()).toSuccessResponse())
            }
            post("/athletes/{id}/heal") { call.respond(heal(call.userId(), call.uuid("id")).toDto(clock.now()).toSuccessResponse()) }
            post("/medical/speedup") { call.respond(speedUpMedical(call.userId()).toDto().toSuccessResponse()) }
            delete("/athletes/{id}") { release(call.userId(), call.uuid("id")); call.respond(mapOf("ok" to true).toSuccessResponse()) }
            get("/athletes/{id}/matches") { call.respond(history(call.userId(), call.uuid("id")).toDto().toSuccessResponse()) }
            post("/athletes/{id}/techniques/{key}") {
                val r = upgradeTechnique(call.userId(), call.uuid("id"), call.parameters["key"] ?: throw ValidationException("Unknown technique"))
                call.respond(UpgradeTechniqueResponse(r.athlete.toDto(clock.now()), r.level, r.cost).toSuccessResponse())
            }
            get("/scouting") {
                val r = scouting.report(call.userId()); val now = clock.now()
                call.respond(ScoutReportDto(r.offers.map { ScoutOfferDto(it.slot, it.kind, it.athlete.toDto(now)) }, r.signedToday, r.nextAt.toString()).toSuccessResponse())
            }
            post("/scouting/{slot}/sign") {
                val slot = call.parameters["slot"]?.toIntOrNull() ?: throw ValidationException("Bad slot")
                call.respond(HttpStatusCode.Created, scouting.sign(call.userId(), slot).toDto(clock.now()).toSuccessResponse(201))
            }
            route("/lineup/{sport}") {
                get {
                    val (lineup, squad) = getLineup(call.userId(), parseEnum<Sport>(call.parameters["sport"]!!, "sport")); val now = clock.now()
                    call.respond(LineupResponse(lineup.toDto(), squad.map { it.toDto(now) }).toSuccessResponse())
                }
                put {
                    val r = call.receive<SetLineupRequest>()
                    val ids = { l: List<String> -> l.map { runCatching { UUID.fromString(it) }.getOrElse { throw ValidationException("Bad athlete id") } } }
                    val l = setLineup(call.userId(), parseEnum<Sport>(call.parameters["sport"]!!, "sport"), ids(r.starters), ids(r.bench), r.tactics)
                    call.respond(l.toDto().toSuccessResponse())
                }
            }
        }
    }
}
