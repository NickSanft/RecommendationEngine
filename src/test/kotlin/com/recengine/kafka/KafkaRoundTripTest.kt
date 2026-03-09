package com.recengine.kafka

import com.recengine.config.AppConfig
import com.recengine.config.KafkaConfig
import com.recengine.model.ClickEvent
import com.recengine.model.RecEngineEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertEquals

/**
 * Phase 1 milestone test: one event produced to Kafka and consumed end-to-end.
 *
 * Uses TestContainers to spin up a real Kafka broker in Docker.
 * Run with: ./gradlew test --tests "com.recengine.kafka.KafkaRoundTripTest"
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaRoundTripTest {

    companion object {
        @Container
        @JvmStatic
        val kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))
    }

    private val json = Json {
        ignoreUnknownKeys  = true
        encodeDefaults     = true
        classDiscriminator = "type"
    }

    private fun buildKafkaConfig() = KafkaConfig(
        bootstrapServers = kafka.bootstrapServers,
        groupId          = "test-consumer",
        topicEvents      = "user-events-test",
        topicUpdates     = "model-updates-test",
        topicFeedback    = "feedback-events-test",
        pollTimeoutMs    = 500,
        maxPollRecords   = 10
    )

    @BeforeAll
    fun setUp() {
        // Create the test topic before producing
        TopicAdmin.createTopics(buildKafkaConfig())
    }

    @Test
    fun `event produced to Kafka is received by consumer`() = runBlocking {
        val cfg      = buildKafkaConfig()
        val producer = KafkaProducerService(cfg, json)
        val consumer = KafkaConsumerService(cfg, json)

        val sentEvent = ClickEvent(
            userId   = "user-123",
            timestampMs = System.currentTimeMillis(),
            itemId   = "item-456",
            sessionId = "session-789"
        )

        var receivedEvent: RecEngineEvent? = null

        // Start consumer before producing so it is ready to receive
        val consumerJob = launch {
            consumer.eventFlow(cfg.topicEvents).collect { event ->
                receivedEvent = event
                return@collect
            }
        }

        // Give the consumer a moment to subscribe
        kotlinx.coroutines.delay(500)

        producer.sendEvent(sentEvent)

        // Wait up to 10 seconds for the event to arrive
        withTimeout(10_000) {
            while (receivedEvent == null) {
                kotlinx.coroutines.delay(100)
            }
        }

        consumerJob.cancel()
        producer.close()

        val received = receivedEvent as ClickEvent
        assertEquals(sentEvent.userId,    received.userId)
        assertEquals(sentEvent.itemId,    received.itemId)
        assertEquals(sentEvent.sessionId, received.sessionId)
    }
}
