package com.matchdayclub.presentation.route

import com.matchdayclub.common.response.toSuccessResponse
import com.matchdayclub.core.plugins.AUTH_JWT
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.rules.BuildingCatalog
import com.matchdayclub.domain.rules.BuildingType
import com.matchdayclub.domain.usecase.club.*
import com.matchdayclub.presentation.dto.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import java.util.UUID

fun Route.clubRoutes() {
    val createClub: CreateClubUseCase by inject()
    val getClub: GetClubUseCase by inject()
    val place: PlaceBuildingUseCase by inject()
    val upgrade: UpgradeBuildingUseCase by inject()
    val collect: CollectIncomeUseCase by inject()
    val speedUp: SpeedUpUseCase by inject()
    val remove: RemoveBuildingUseCase by inject()

    get("/catalog/buildings") { call.respond(BuildingCatalog.specs.values.map { it.toDto() }.toSuccessResponse()) }

    authenticate(AUTH_JWT) {
        route("/club") {
            post {
                val r = call.receive<CreateClubRequest>()
                val club = createClub(call.userId(), r.name, r.primaryColor, r.secondaryColor, r.timezone)
                call.respond(HttpStatusCode.Created, club.toDto().toSuccessResponse(201))
            }
            get {
                val st = getClub(call.userId())
                call.respond(ClubStateResponse(st.club.toDto(), st.buildings.map { it.toDto(st.now, st.club.level) }, st.gridSize, st.now.toString()).toSuccessResponse())
            }
            route("/buildings") {
                post {
                    val r = call.receive<PlaceBuildingRequest>()
                    val type = runCatching { BuildingType.valueOf(r.type) }.getOrElse { throw ValidationException("Unknown building type") }
                    val st = getClub(call.userId())
                    val b = place(call.userId(), type, r.x, r.y, r.rotation)
                    call.respond(HttpStatusCode.Created, b.toDto(st.now, st.club.level).toSuccessResponse(201))
                }
                route("/{id}") {
                    fun RoutingCall.buildingId() = runCatching { UUID.fromString(parameters["id"]) }.getOrElse { throw ValidationException("Bad building id") }
                    post("/upgrade") { val st = getClub(call.userId()); call.respond(upgrade(call.userId(), call.buildingId()).toDto(st.now, st.club.level).toSuccessResponse()) }
                    post("/speedup") { val st = getClub(call.userId()); call.respond(speedUp(call.userId(), call.buildingId()).toDto(st.now, st.club.level).toSuccessResponse()) }
                    post("/collect") {
                        val r = collect(call.userId(), call.buildingId())
                        call.respond(CollectResponse(r.building.toDto(r.building.updatedAt, r.club.level), r.coins, r.club.toDto()).toSuccessResponse())
                    }
                    delete { remove(call.userId(), call.buildingId()); call.respond(mapOf("ok" to true).toSuccessResponse()) }
                }
            }
        }
    }
}
