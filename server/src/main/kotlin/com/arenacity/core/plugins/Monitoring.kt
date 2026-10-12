package com.arenacity.core.plugins

import com.arenacity.core.metrics.Metrics
import io.ktor.server.application.*
import io.ktor.server.application.hooks.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.util.*
import org.slf4j.event.Level

private val StartedAt = AttributeKey<Long>("arenacity-metrics-start")
private val RouteTemplate = AttributeKey<String>("arenacity-metrics-route")
private val selector = Regex("""\([^()]*\)""")
private val slashes = Regex("/{2,}")
private val StandardMethods = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

/** Route template of a matched call ("/api/v1/club/buildings/{id}/speedup"), without method/auth/rate-limit selectors. */
internal fun routeTemplate(node: RoutingNode): String = cleanTemplate(node.toString())

internal fun cleanTemplate(path: String): String {
    var s = path
    while (true) { val n = selector.replace(s, ""); if (n == s) break; s = n }
    return slashes.replace(s, "/").trimEnd('/').ifEmpty { "/" }
}

/** Request counts by route template and status, overall latency histogram (unmatched paths share one label). */
val HttpMetrics = createApplicationPlugin("HttpMetrics") {
    Metrics.counter("http_requests_total", "HTTP requests by route template, method and status")
    Metrics.histogram("http_request_duration_seconds", "HTTP request latency")
    application.monitor.subscribe(RoutingRoot.RoutingCallStarted) { call -> call.attributes.put(RouteTemplate, routeTemplate(call.route)) }
    onCall { call -> call.attributes.put(StartedAt, System.nanoTime()) }
    on(ResponseSent) { call ->
        val start = call.attributes.getOrNull(StartedAt) ?: return@on
        val route = call.attributes.getOrNull(RouteTemplate) ?: "unmatched"
        if (route == "/metrics") return@on
        val status = call.response.status()?.value ?: 0
        // any method string reaches us; only standard ones become label values (no unbounded series from scanners)
        val method = call.request.httpMethod.value.uppercase().takeIf { it in StandardMethods } ?: "OTHER"
        Metrics.inc("http_requests_total", "route" to route, "method" to method, "status" to status.toString())
        Metrics.observe("http_request_duration_seconds", (System.nanoTime() - start) / 1e9)
    }
}

fun Application.configureMonitoring() {
    install(CallLogging) {
        level = Level.INFO
        format { call -> "[${call.attributes.getOrNull(RequestIdKey) ?: "-"}] ${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()}" }
    }
    install(HttpMetrics)
    Metrics.registerJvm()
}
