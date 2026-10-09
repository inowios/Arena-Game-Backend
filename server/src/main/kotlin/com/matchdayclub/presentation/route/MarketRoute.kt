package com.matchdayclub.presentation.route

import com.matchdayclub.common.response.toSuccessResponse
import com.matchdayclub.common.time.Clock
import com.matchdayclub.core.plugins.AUTH_JWT
import com.matchdayclub.domain.exception.ValidationException
import com.matchdayclub.domain.rules.Market
import com.matchdayclub.domain.usecase.market.*
import com.matchdayclub.presentation.dto.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import java.util.UUID

private fun RoutingCall.uuid(name: String) = runCatching { UUID.fromString(parameters[name]) }.getOrElse { throw ValidationException("Bad $name") }

/** The transfer market: listings, buying, refreshing, and selling your own athletes. */
fun Route.marketRoutes() {
    val get: GetMarketUseCase by inject()
    val buy: BuyListingUseCase by inject()
    val refresh: RefreshMarketUseCase by inject()
    val sell: SellAthleteUseCase by inject()
    val clock: Clock by inject()
    authenticate(AUTH_JWT) {
        route("/market") {
            get { call.respond(get(call.userId()).toDto(clock.now()).toSuccessResponse()) }
            post("/{index}/buy") {
                val idx = call.parameters["index"]?.toIntOrNull()?.takeIf { it in 0 until Market.LISTINGS } ?: throw ValidationException("Bad listing")
                val (a, club) = buy(call.userId(), idx)
                call.respond(BuyListingDto(a.toDto(clock.now()), club.toDto()).toSuccessResponse())
            }
            post("/refresh") { call.respond(refresh(call.userId()).toDto(clock.now()).toSuccessResponse()) }
        }
        post("/club/athletes/{id}/sell") { val (coins, club) = sell(call.userId(), call.uuid("id")); call.respond(SellAthleteDto(coins, club.toDto()).toSuccessResponse()) }
    }
}
