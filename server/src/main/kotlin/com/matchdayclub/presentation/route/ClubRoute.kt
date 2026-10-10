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
    val updateClub: UpdateClubUseCase by inject()
    val move: MoveBuildingUseCase by inject()
    val buyBuilder: BuyBuilderUseCase by inject()
    val placeFences: PlaceFencesUseCase by inject()
    val upgradeFences: UpgradeFencesUseCase by inject()
    val applyLayout: ApplyLayoutUseCase by inject()
    val layouts: LayoutUseCases by inject()
    val clearObstacle: ClearObstacleUseCase by inject()
    val speedObstacle: SpeedUpObstacleUseCase by inject()
    val collectObstacle: CollectObstacleUseCase by inject()
    val sections: com.matchdayclub.domain.usecase.athlete.SectionService by inject()
    val cosmetics: com.matchdayclub.domain.usecase.economy.CosmeticsUseCases by inject()
    val inbox: com.matchdayclub.domain.usecase.account.InboxService by inject()
    val readiness: com.matchdayclub.domain.usecase.live.LiveReadinessUseCase by inject()
    val rest: com.matchdayclub.domain.usecase.live.RestSquadUseCase by inject()

    get("/catalog/buildings") { call.respond(BuildingCatalog.specs.values.map { it.toDto() }.toSuccessResponse()) }

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
                val active = sections.active(st.club, clubBuildings = st.buildings).map { it.name }
                val atmosphere = com.matchdayclub.domain.rules.Atmosphere.of(st.buildings)
                call.respond(ClubStateResponse(st.club.toDto(), st.buildings.map { it.toDto(st.now, st.club.level, atmosphere[it.id] ?: com.matchdayclub.domain.rules.Atmosphere.Bonus()) }, st.gridSize, st.now.toString(), active, st.club.adsState(st.now), inbox.unread(st.club.id), readiness.of(st.club).toDto(), st.stadium, st.obstacles.map { it.toDto(st.now, st.stadium) }).toSuccessResponse())
            }
            post("/rest") { call.respond(rest(call.userId()).toDto().toSuccessResponse()) }
            get("/inbox") {
                val club = getClub(call.userId()).club
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
                call.respond(InboxResponse(inbox.list(club.id, limit).map { it.toDto() }, inbox.unread(club.id)).toSuccessResponse())
            }
            post("/inbox/read") { inbox.markAllRead(getClub(call.userId()).club.id); call.respond(mapOf("ok" to true).toSuccessResponse()) }
            post("/builders") { call.respond(buyBuilder(call.userId()).toDto().toSuccessResponse()) }
            route("/obstacles/{id}") {
                fun RoutingCall.obstacleId() = runCatching { UUID.fromString(parameters["id"]) }.getOrElse { throw ValidationException("Bad obstacle id") }
                post("/clear") { val o = clearObstacle(call.userId(), call.obstacleId()); val st = getClub(call.userId()); call.respond(o.toDto(st.now, st.stadium).toSuccessResponse()) }
                post("/speedup") { val o = speedObstacle(call.userId(), call.obstacleId()); val st = getClub(call.userId()); call.respond(o.toDto(st.now, st.stadium).toSuccessResponse()) }
                post("/collect") { val r = collectObstacle(call.userId(), call.obstacleId()); call.respond(ObstacleLootResponse(r.gold, r.club.toDto()).toSuccessResponse()) }
            }
            fun uuid(s: String) = runCatching { UUID.fromString(s) }.getOrElse { throw ValidationException("Bad building id") }
            post("/fences") {
                val r = call.receive<PlaceFencesRequest>()
                val placed = placeFences(call.userId(), r.cells.map { it.x to it.y })
                val st = getClub(call.userId())
                call.respond(HttpStatusCode.Created, placed.map { it.toDto(st.now, st.club.level) }.toSuccessResponse(201))
            }
            post("/fences/upgrade") {
                val r = call.receive<IdsRequest>()
                val done = upgradeFences(call.userId(), r.ids.map(::uuid))
                val st = getClub(call.userId())
                call.respond(done.map { it.toDto(st.now, st.club.level) }.toSuccessResponse())
            }
            put("/layout") {
                val r = call.receive<LayoutRequest>()
                val moved = applyLayout(call.userId(), r.moves.map { Placement(uuid(it.id), it.x, it.y, it.rotation) })
                call.respond(mapOf("moved" to moved.size).toSuccessResponse())
            }
            route("/layouts") {
                get { call.respond(layouts.list(call.userId()).map { it.toDto() }.toSuccessResponse()) }
                route("/{slot}") {
                    fun RoutingCall.slot() = parameters["slot"]?.toIntOrNull() ?: throw ValidationException("Unknown layout")
                    put { val r = call.receive<SaveLayoutRequest>(); call.respond(layouts.save(call.userId(), call.slot(), r.name).toDto().toSuccessResponse()) }
                    post("/apply") { val moved = layouts.applySlot(call.userId(), call.slot()); call.respond(mapOf("moved" to moved.size).toSuccessResponse()) }
                }
            }
            route("/buildings") {
                post {
                    val r = call.receive<PlaceBuildingRequest>()
                    val type = runCatching { BuildingType.valueOf(r.type) }.getOrElse { throw ValidationException("Unknown building type") }
                    val st = getClub(call.userId())
                    val b = place(call.userId(), type, r.x, r.y, r.rotation, r.variant)
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
                    post("/move") {
                        val r = call.receive<MoveBuildingRequest>()
                        val b = move(call.userId(), call.buildingId(), r.x, r.y, r.rotation)
                        val st = getClub(call.userId())
                        call.respond(b.toDto(st.now, st.club.level).toSuccessResponse())
                    }
                    post("/variant") {
                        val r = call.receive<VariantRequest>()
                        val b = cosmetics.setDecorLook(call.userId(), call.buildingId(), r.itemId)
                        val st = getClub(call.userId())
                        call.respond(b.toDto(st.now, st.club.level).toSuccessResponse())
                    }
                    delete { remove(call.userId(), call.buildingId()); call.respond(mapOf("ok" to true).toSuccessResponse()) }
                }
            }
        }
    }
}
