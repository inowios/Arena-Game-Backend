package com.arenacity.presentation.route

import com.arenacity.common.response.toSuccessResponse
import com.arenacity.core.metrics.Metrics
import com.arenacity.core.plugins.SchedulerStatus
import com.arenacity.core.plugins.SchedulerTick
import com.arenacity.domain.exception.AuthenticationException
import com.arenacity.domain.exception.ValidationException
import com.arenacity.domain.repository.OpsRepository
import com.arenacity.domain.usecase.admin.AdminGrantUseCase
import com.arenacity.domain.usecase.admin.SupportQueries
import com.arenacity.domain.usecase.analytics.MetricsReportUseCase
import com.arenacity.presentation.dto.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/** Shortest admin token accepted; with a shorter (or no) ADMIN_TOKEN the admin endpoints and /metrics are not served. */
const val ADMIN_TOKEN_MIN = 16

private fun ApplicationCall.requireAdmin(expected: ByteArray) {
    val got = request.header("X-Admin-Token")
        ?: request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
    if (got == null || !MessageDigest.isEqual(got.toByteArray(), expected)) throw AuthenticationException("Admin token required")
}

/**
 * Ops and support API (header `X-Admin-Token: <ADMIN_TOKEN>` or `Authorization: Bearer <ADMIN_TOKEN>`):
 * Prometheus metrics, the launch KPI report, counts and scheduler state, club lookup, compensation grants and a manual
 * scheduler pass.
 */
fun Route.adminRoutes(token: String) {
    val expected = token.toByteArray()
    val report: MetricsReportUseCase by inject()
    val ops: OpsRepository by inject()
    val support: SupportQueries by inject()
    val grant: AdminGrantUseCase by inject()
    val tick: SchedulerTick by inject()
    val liveOps: com.arenacity.domain.usecase.liveops.LiveOps by inject()
    val funnel: com.arenacity.domain.usecase.analytics.FunnelUseCase by inject()
    val balance: com.arenacity.domain.usecase.analytics.BalanceUseCase by inject()
    val version = AdminRoute::class.java.`package`?.implementationVersion ?: "dev"

    get("/metrics") {
        call.requireAdmin(expected)
        call.respondText(Metrics.render(), ContentType.parse("text/plain; version=0.0.4; charset=utf-8"))
    }
    route("/admin") {
        get("/metrics") {
            call.requireAdmin(expected)
            val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 30
            val to = call.request.queryParameters["to"]?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw ValidationException("Bad date") } }
            call.respond(report(days, to).toDto().toSuccessResponse())
        }
        // the first-ten-minutes funnel and the balance dashboard (JSON); /admin/dashboard is a page that reads both
        get("/funnel") {
            call.requireAdmin(expected)
            val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 14
            val to = call.request.queryParameters["to"]?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw ValidationException("Bad date") } }
            call.respond(funnel(days, to).toDto().toSuccessResponse())
        }
        get("/balance") {
            call.requireAdmin(expected)
            val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 7
            val to = call.request.queryParameters["to"]?.let { runCatching { LocalDate.parse(it) }.getOrElse { throw ValidationException("Bad date") } }
            call.respond(balance(days, to).toDto().toSuccessResponse())
        }
        get("/dashboard") { call.respondText(DashboardPage.HTML, ContentType.Text.Html) }
        get("/stats") {
            call.requireAdmin(expected)
            val s = SchedulerStatus
            call.respond(OpsStatsDto(ops.counts(), SchedulerStatusDto(s.lastTickAt?.toString(), s.lastMatches, s.lastCupMatches, s.lastError, s.ticks.get(), s.failures.get()), version).toSuccessResponse())
        }
        get("/clubs") {
            call.requireAdmin(expected)
            call.respond(support.search(call.request.queryParameters["q"] ?: "").map { it.toAdminDto() }.toSuccessResponse())
        }
        get("/clubs/{id}") {
            call.requireAdmin(expected)
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrElse { throw ValidationException("Bad id") }
            call.respond(support.card(id).toDto().toSuccessResponse())
        }
        post("/clubs/{id}/grant") {
            call.requireAdmin(expected)
            val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrElse { throw ValidationException("Bad id") }
            val r = call.receive<GrantRequest>()
            call.respond(grant(id, r.coins, r.gold, r.reason).toAdminDto().toSuccessResponse())
        }
        // live game: events (on top of the built-in calendar) and developer mail with gifts
        get("/events") { call.requireAdmin(expected); call.respond(liveOps.adminEvents().map { it.toDto() }.toSuccessResponse()) }
        post("/events") {
            call.requireAdmin(expected)
            val r = call.receive<CreateEventRequest>()
            val at = { s: String -> runCatching { java.time.Instant.parse(s) }.getOrElse { throw ValidationException("Bad date") } }
            call.respond(liveOps.createEvent(r.key, at(r.startsAt), at(r.endsAt), r.buildTimePct, r.incomePct, r.obstacleGoldPct, r.goal, r.goalTarget, r.rewardCoins, r.rewardGold, r.rewardItem).toDto().toSuccessResponse())
        }
        delete("/events/{id}") { call.requireAdmin(expected); liveOps.deleteEvent(call.parameters["id"] ?: ""); call.respond(mapOf("ok" to true).toSuccessResponse()) }
        post("/mail") {
            call.requireAdmin(expected)
            val r = call.receive<SendMailRequest>()
            val club = r.clubId?.let { runCatching { UUID.fromString(it) }.getOrElse { throw ValidationException("Bad id") } }
            val m = liveOps.sendMail(r.title, r.body, r.coins, r.gold, r.item, club, r.days)
            call.respond(mapOf("id" to m.id.toString()).toSuccessResponse())
        }
        post("/scheduler/run") {
            call.requireAdmin(expected)
            val (matches, cup) = tick.run()
            call.respond(SchedulerRunResponse(matches, cup).toSuccessResponse())
        }
    }
}

private object AdminRoute
