package com.recengine.dashboard

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
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

        get("/shop") {
            val html = this::class.java.classLoader
                .getResourceAsStream("static/shop.html")
                ?.readBytes()?.toString(Charsets.UTF_8)
                ?: "<h1>shop.html not found</h1>"
            call.respondText(html, ContentType.Text.Html)
        }

        post("/events/clear") {
            broadcaster.clearReplayCache()
            call.respond(HttpStatusCode.NoContent)
        }

        sse("/events/stream") {
            log.info { "SSE client connected" }

            // Send immediately to flush HTTP response headers to the browser.
            // Without this, Netty buffers the headers until the first real event
            // arrives, leaving the browser's EventSource stuck in CONNECTING state.
            send(ServerSentEvent(data = "connected", event = "ping"))

            val heartbeat = flow {
                while (true) {
                    delay(30_000)
                    emit(ServerSentEvent(data = "heartbeat", event = "ping"))
                }
            }

            try {
                // merge serialises events from both flows through a single collect,
                // so send() is never called concurrently.
                merge(
                    broadcaster.events.map { event ->
                        ServerSentEvent(
                            data  = json.encodeToString(event),
                            event = event::class.simpleName,
                        )
                    },
                    heartbeat,
                ).collect { sseEvent ->
                    send(sseEvent)
                }
            } finally {
                log.info { "SSE client disconnected" }
            }
        }
    }
}
