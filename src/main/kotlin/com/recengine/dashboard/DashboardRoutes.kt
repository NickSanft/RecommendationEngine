package com.recengine.dashboard

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val log = KotlinLogging.logger {}

fun Application.dashboardRoutes(broadcaster: EventBroadcaster, json: Json) {
    routing {

        get("/") {
            val html = this::class.java.classLoader
                .getResourceAsStream("static/dashboard.html")
                ?.readBytes()
                ?.toString(Charsets.UTF_8)
                ?: "<h1>dashboard.html not found in classpath</h1>"
            call.respondText(html, ContentType.Text.Html)
        }

        sse("/events/stream") {
            log.info { "SSE client connected" }
            try {
                broadcaster.events.collect { event ->
                    val payload = json.encodeToString(event)
                    send(ServerSentEvent(data = payload, event = event::class.simpleName))
                }
            } finally {
                log.info { "SSE client disconnected" }
            }
        }
    }
}
