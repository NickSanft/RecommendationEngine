package com.recengine.routing

import com.recengine.kafka.KafkaProducerService
import com.recengine.model.ClickEvent
import com.recengine.model.ImpressionEvent
import com.recengine.model.PurchaseEvent
import com.recengine.model.RecEngineEvent
import com.recengine.model.ViewEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.random.Random

private val log = KotlinLogging.logger {}

@Serializable
data class SeedResult(
    val sent: Int,
    val types: Map<String, Int>
)

fun Application.devRoutes(producer: KafkaProducerService) {
    routing {
        /**
         * POST /dev/seed?count=20&type=random
         *
         * Produces [count] synthetic events to Kafka (default 20, max 500).
         * All sends run concurrently and the response is returned once every
         * event has been acknowledged by the broker.
         *
         * Optional [type] query param: click | view | purchase | impression | random (default)
         */
        post("/dev/seed") {
            val count    = call.request.queryParameters["count"]?.toIntOrNull()?.coerceIn(1, 500) ?: 20
            val typeFilter = call.request.queryParameters["type"]?.lowercase()

            val events = (1..count).map { randomEvent(typeFilter) }

            try {
                coroutineScope {
                    events.forEach { event -> launch { producer.sendEvent(event) } }
                }
                val typeCounts = events.groupingBy { it::class.simpleName ?: "Unknown" }.eachCount()
                log.info { "Seeded $count events to Kafka: $typeCounts" }
                call.respond(SeedResult(sent = count, types = typeCounts))
            } catch (e: Exception) {
                log.error(e) { "Seed failed" }
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    SeedResult(sent = 0, types = mapOf("error" to 1))
                )
            }
        }
    }
}

// ── Synthetic data pools ──────────────────────────────────────────────────

private val userIds  = (1..20).map  { "user-${it.toString().padStart(3, '0')}" }
private val itemIds  = (1..50).map  { "item-${it.toString().padStart(3, '0')}" }
private val variants = listOf("control", "fm_v1", "bandit_ucb")
private val categories = listOf("electronics", "books", "clothing", "home", "sports")
private val tags       = listOf("sale", "new", "trending", "premium", "clearance")

private fun sessionId(userId: String) = "session-${userId.takeLast(3)}-${Random.nextInt(1, 5)}"

private fun randomEvent(typeFilter: String? = null): RecEngineEvent {
    val userId = userIds.random()
    val itemId = itemIds.random()
    val sessId = sessionId(userId)
    val now    = System.currentTimeMillis()

    return when (typeFilter) {
        "click"      -> click(userId, itemId, sessId, now)
        "view"       -> view(userId, itemId, sessId, now)
        "purchase"   -> purchase(userId, itemId, sessId, now)
        "impression" -> impression(userId, sessId, now)
        else -> when (Random.nextInt(10)) {
            // 40% view, 40% click, 10% purchase, 10% impression
            in 0..3 -> view(userId, itemId, sessId, now)
            in 4..7 -> click(userId, itemId, sessId, now)
            8       -> purchase(userId, itemId, sessId, now)
            else    -> impression(userId, sessId, now)
        }
    }
}

private fun click(userId: String, itemId: String, sessionId: String, now: Long) =
    ClickEvent(
        userId           = userId,
        timestampMs      = now,
        itemId           = itemId,
        sessionId        = sessionId,
        recommendationId = if (Random.nextBoolean()) "rec-${Random.nextInt(1000)}" else null
    )

private fun view(userId: String, itemId: String, sessionId: String, now: Long) =
    ViewEvent(
        userId         = userId,
        timestampMs    = now,
        itemId         = itemId,
        sessionId      = sessionId,
        dwellSeconds   = (Random.nextDouble(2.0, 90.0) * 10).toLong() / 10.0,
        sourcePosition = Random.nextInt(1, 21)
    )

private fun purchase(userId: String, itemId: String, sessionId: String, now: Long) =
    PurchaseEvent(
        userId      = userId,
        timestampMs = now,
        itemId      = itemId,
        sessionId   = sessionId,
        revenue     = (Random.nextDouble(4.99, 499.99) * 100).toLong() / 100.0,
        quantity    = Random.nextInt(1, 4)
    )

private fun impression(userId: String, sessionId: String, now: Long) =
    ImpressionEvent(
        userId      = userId,
        timestampMs = now,
        itemIds     = (1..Random.nextInt(3, 9)).map { itemIds.random() }.distinct(),
        sessionId   = sessionId,
        variantId   = variants.random()
    )
