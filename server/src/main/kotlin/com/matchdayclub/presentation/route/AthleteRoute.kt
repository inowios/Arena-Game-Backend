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
    val scouting: ScoutingUseCases by inject()
    val getLineup: GetLineupUseCase by inject()
    val setLineup: SetLineupUseCase by inject()
    val clock: Clock by inject()

    authenticate(AUTH_JWT) {
        route("/club") {
            get("/athletes") {
                val s = list(call.userId()); val now = clock.now()
                call.respond(SquadResponse(s.athletes.map { it.toDto(now) }, s.trainings.map { it.toDto(now) }, s.slots, now.toString()).toSuccessResponse())
            }
            post("/academy/claim") {
                val sport = runCatching { call.receive<AcademyClaimRequest>() }.getOrNull()?.sport?.let { parseEnum<Sport>(it, "sport") } ?: Sport.FOOTBALL
                call.respond(HttpStatusCode.Created, claim(call.userId(), sport).toDto(clock.now()).toSuccessResponse(201))
            }
            post("/trainings") {
                val r = call.receive<StartTrainingRequest>()
                val t = start(call.userId(), runCatching { UUID.fromString(r.athleteId) }.getOrElse { throw ValidationException("Bad athleteId") }, parseEnum<AttributeKind>(r.attribute, "attribute"))
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
