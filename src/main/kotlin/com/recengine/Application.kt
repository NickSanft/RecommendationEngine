package com.recengine

import com.recengine.config.AppConfig
import com.recengine.kafka.KafkaProducerService
import com.recengine.kafka.TopicAdmin
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    val config = AppConfig.load()
    val json   = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

    // Ensure Kafka topics exist on startup
    try {
        TopicAdmin.createTopics(config.kafka)
    } catch (e: Exception) {
        logger.warn(e) { "Could not create Kafka topics on startup — Kafka may not be available yet" }
    }

    install(ContentNegotiation) { json(json) }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to cause.message))
        }
        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled exception" }
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal server error"))
        }
    }

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok", "version" to "0.1.0"))
        }

        get("/health/kafka") {
            try {
                // Attempt a lightweight admin list — passes if Kafka is reachable
                val producer = KafkaProducerService(config.kafka, json)
                producer.close()
                call.respond(mapOf("kafka" to "ok"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("kafka" to "unavailable", "error" to e.message))
            }
        }
    }

    logger.info { "RecEngine started — Kafka=${config.kafka.bootstrapServers} Redis=${config.redis.uri}" }
}
