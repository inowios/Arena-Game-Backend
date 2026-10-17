package com.arenacity.presentation.route

import com.arenacity.common.response.toSuccessResponse
import com.arenacity.core.plugins.AUTH_JWT
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.rules.MapSpots
import com.arenacity.domain.usecase.club.*
import com.arenacity.presentation.dto.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.clubRoutes() {
    val createClub: CreateClubUseCase by inject()
    val getClub: GetClubUseCase by inject()
    val unlockSpot: UnlockSpotUseCase by inject()
    val updateClub: UpdateClubUseCase by inject()
    val roads: com.arenacity.domain.usecase.progress.RoadUseCases by inject()
    val collections: com.arenacity.domain.usecase.progress.CollectionsUseCases by inject()
    val museum: com.arenacity.domain.usecase.progress.MuseumService by inject()
    val sections: com.arenacity.domain.usecase.athlete.SectionService by inject()
    val inbox: com.arenacity.domain.usecase.account.InboxService by inject()
    val readiness: com.arenacity.domain.usecase.live.LiveReadinessUseCase by inject()
    val rest: com.arenacity.domain.usecase.live.RestSquadUseCase by inject()
    val zonesUc: com.arenacity.domain.usecase.club.ZoneUseCases by inject()
    val energy: EnergyUseCases by inject()

    // the five islands map's spots (rules.MapSpots)
    get("/catalog/spots") { call.respond(MapSpots.ALL.map { it.toDto() }.toSuccessResponse()) }

    authenticate(AUTH_JWT) {
        route("/club") {
            post {
                val r = call.receive<CreateClubRequest>()
                val club = createClub(call.userId(), r.name, r.primaryColor, r.secondaryColor, r.timezone)
                call.respond(HttpStatusCode.Created, club.toDto().toSuccessResponse(201))
            }
            patch {
                val r = call.receive<UpdateClubRequest>()
                call.respond(updateClub(call.userId(), r.name, r.primaryColor, r.secondaryColor, r.emblemJson).toDto().toSuccessResponse())
            }
            get {
                val st = getClub(call.userId())
                call.respond(st.toResponse(sections.active(st.club).map { it.name }, inbox.unread(st.club.id), readiness.of(st.club).toDto()).toSuccessResponse())
            }
            // opens a main-island spot for its coins; answers the club as GET /club does
            post("/spots/{id}/unlock") {
                val st = unlockSpot(call.userId(), call.parameters["id"].orEmpty())
                call.respond(st.toResponse(sections.active(st.club).map { it.name }, inbox.unread(st.club.id), readiness.of(st.club).toDto()).toSuccessResponse())
            }
            // energy (rules.Energy): what the club has now, and filling it up for gems
            get("/energy") { call.respond(energy.state(call.userId()).toDto().toSuccessResponse()) }
            post("/energy/refill") {
                val (club, e) = energy.refill(call.userId())
                call.respond(EnergyRefillResponse(e.toDto(), club.toDto()).toSuccessResponse())
            }
            post("/rest") { call.respond(rest(call.userId()).toDto().toSuccessResponse()) }
            // the bridges to the peoples' islands (rules.Zones); {people}: BEAST, STONE, ANDROID, SHADOW (an old client's sport name reads as its island's people)
            get("/zones") { call.respond(zonesUc.list(call.userId()).map { it.toDto() }.toSuccessResponse()) }
            post("/zones/{sport}/buy") {
                val people = com.arenacity.domain.model.People.parse(call.parameters["sport"]) ?: throw com.arenacity.domain.exception.ValidationException("Unknown zone")
                call.respond(HttpStatusCode.Created, zonesUc.buy(call.userId(), people).toDto().toSuccessResponse(201))
            }
            get("/inbox") {
                val club = getClub(call.userId()).club
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                call.respond(InboxResponse(inbox.list(club.id, limit).map { it.toDto() }, inbox.unread(club.id)).toSuccessResponse())
            }
            post("/inbox/read") { inbox.markAllRead(getClub(call.userId()).club.id); call.respond(mapOf("ok" to true).toSuccessResponse()) }
            get("/roads") { call.respond(roads.list(call.userId()).map { it.toDto() }.toSuccessResponse()) }
            post("/roads/{road}/{step}/claim") {
                val road = runCatching { com.arenacity.domain.rules.Road.valueOf(call.parameters["road"]!!.uppercase()) }.getOrElse { throw ValidationException("Unknown reward") }
                val step = call.parameters["step"]?.toIntOrNull() ?: throw ValidationException("Unknown reward")
                val r = roads.claim(call.userId(), road, step)
                call.respond(r.toDto(java.time.Instant.now()).toSuccessResponse())
            }
            get("/collections") { call.respond(collections.list(call.userId()).toDto().toSuccessResponse()) }
            get("/museum") { call.respond(museum.of(getClub(call.userId()).club.id).toDto().toSuccessResponse()) }
        }
    }
}
