package com.arenacity.presentation.route

import com.arenacity.common.response.toSuccessResponse
import com.arenacity.core.plugins.AUTH_JWT
import com.arenacity.domain.usecase.analytics.ClientEvent
import com.arenacity.domain.usecase.analytics.TrackEventsUseCase
import com.arenacity.presentation.dto.ClientEventsRequest
import com.arenacity.presentation.dto.ClientEventsResponse
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/** Product events from the app, sent in batches (tutorial funnel, store, ads, shares, sessions). */
fun Route.analyticsRoutes() {
    val track: TrackEventsUseCase by inject()
    authenticate(AUTH_JWT) {
        post("/analytics/events") {
            val r = call.receive<ClientEventsRequest>()
            val n = track(call.userId(), r.events.map { ClientEvent(it.name, it.props, it.at) }, r.platform, r.appVersion)
            call.respond(ClientEventsResponse(n).toSuccessResponse())
        }
    }
}
