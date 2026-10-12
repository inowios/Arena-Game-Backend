package com.arenacity.presentation.route

import com.arenacity.common.response.toSuccessResponse
import com.arenacity.core.plugins.AUTH_JWT
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.rules.BuildingCatalog
import com.arenacity.domain.rules.BuildingType
import com.arenacity.domain.usecase.club.*
import com.arenacity.presentation.dto.*
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
    val roads: com.arenacity.domain.usecase.progress.RoadUseCases by inject()
    val collections: com.arenacity.domain.usecase.progress.CollectionsUseCases by inject()
    val museum: com.arenacity.domain.usecase.progress.MuseumService by inject()
    val clubService: ClubService by inject()
    val clearObstacle: ClearObstacleUseCase by inject()
    val speedObstacle: SpeedUpObstacleUseCase by inject()
    val collectObstacle: CollectObstacleUseCase by inject()
    val sections: com.arenacity.domain.usecase.athlete.SectionService by inject()
    val cosmetics: com.arenacity.domain.usecase.economy.CosmeticsUseCases by inject()
    val inbox: com.arenacity.domain.usecase.account.InboxService by inject()
    val readiness: com.arenacity.domain.usecase.live.LiveReadinessUseCase by inject()
    val rest: com.arenacity.domain.usecase.live.RestSquadUseCase by inject()
    val zonesUc: com.arenacity.domain.usecase.club.ZoneUseCases by inject()
    val landUc: com.arenacity.domain.usecase.club.LandUseCases by inject()

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
                val atmosphere = com.arenacity.domain.rules.Atmosphere.of(st.buildings, collections.incomePct(st.club.id), clubService.effects().incomePct - 100)
                call.respond(ClubStateResponse(st.club.toDto(), st.buildings.map { it.toDto(st.now, st.club.level, atmosphere[it.id] ?: com.arenacity.domain.rules.Atmosphere.Bonus()) }, st.gridSize, st.now.toString(), active, st.club.adsState(st.now), inbox.unread(st.club.id), readiness.of(st.club).toDto(), st.stadium, st.obstacles.map { it.toDto(st.now, st.stadium) },
                    st.parcels.map { ParcelDto(it.first, it.second) }, landUc.view(st).toDto()).toSuccessResponse())
            }
            post("/rest") { call.respond(rest(call.userId()).toDto().toSuccessResponse()) }
            // the stadium zones of the club's rectangle (CITY-BUILD-PLAN stage A)
            // the land round the club: buy the parcel (col, row) next to your land (closed since the archipelago: 400 "Land is not for sale")
            post("/land/{col}/{row}/buy") {
                val col = call.parameters["col"]?.toIntOrNull() ?: throw ValidationException("Bad parcel"); val row = call.parameters["row"]?.toIntOrNull() ?: throw ValidationException("Bad parcel")
                val view = landUc.buy(call.userId(), col, row)
                val st = getClub(call.userId())
                call.respond(HttpStatusCode.Created, LandResponse(view.parcels.map { ParcelDto(it.first, it.second) }, view.toDto(), st.club.toDto()).toSuccessResponse(201))
            }
            get("/zones") { call.respond(zonesUc.list(call.userId()).map { it.toDto() }.toSuccessResponse()) }
            post("/zones/{sport}/buy") {
                val sport = runCatching { com.arenacity.domain.model.ZoneSport.valueOf(call.parameters["sport"]!!.uppercase()) }.getOrElse { throw com.arenacity.domain.exception.ValidationException("Unknown zone") }
                call.respond(HttpStatusCode.Created, zonesUc.buy(call.userId(), sport).toDto().toSuccessResponse(201))
            }
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
                val drawn = runCatching { com.arenacity.domain.rules.BuildingType.valueOf(r.type) }.getOrElse { throw ValidationException("Unknown type") }
                val placed = placeFences(call.userId(), r.cells.map { it.x to it.y }, drawn)
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
            get("/roads") { call.respond(roads.list(call.userId()).map { it.toDto() }.toSuccessResponse()) }
            post("/roads/{road}/{step}/claim") {
                val road = runCatching { com.arenacity.domain.rules.Road.valueOf(call.parameters["road"]!!.uppercase()) }.getOrElse { throw ValidationException("Unknown reward") }
                val step = call.parameters["step"]?.toIntOrNull() ?: throw ValidationException("Unknown reward")
                val r = roads.claim(call.userId(), road, step)
                call.respond(r.toDto(java.time.Instant.now()).toSuccessResponse())
            }
            get("/collections") { call.respond(collections.list(call.userId()).toDto().toSuccessResponse()) }
            get("/museum") { call.respond(museum.of(getClub(call.userId()).club.id).toDto().toSuccessResponse()) }
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
