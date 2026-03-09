package com.recengine.kafka

import com.recengine.config.KafkaConfig
import com.recengine.model.ClickEvent
import com.recengine.model.RecEngineEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Properties
import kotlin.test.assertEquals

/**
 * Phase 1 milestone test: one event produced to Kafka and consumed end-to-end.
 *
 * Requires docker-compose services to be running:
 *   docker-compose up -d
 *
 * Run with: .\gradlew.bat test --tests "com.recengine.kafka.KafkaRoundTripTest"
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaRoundTripTest {

    private val bootstrapServers = "localhost:9092"

    private val json = Json {
        ignoreUnknownKeys  = true
        encodeDefaults     = true
        classDiscriminator = "type"
    }

    private val cfg = KafkaConfig(
        bootstrapServers = bootstrapServers,
        groupId          = "test-consumer-${System.currentTimeMillis()}",
        topicEvents      = "user-events-test",
        topicUpdates     = "model-updates-test",
        topicFeedback    = "feedback-events-test",
        pollTimeoutMs    = 500,
        maxPollRecords   = 10
    )

    @BeforeAll
    fun checkKafkaAvailable() {
        // Skip the test gracefully if docker-compose Kafka is not running
        val available = try {
            val props = Properties().apply {
                put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
                put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000")
                put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "3000")
            }
            AdminClient.create(props).use { it.listTopics().names().get() }
            true
        } catch (e: Exception) {
            false
        }
        assumeTrue(available, "Kafka not reachable at $bootstrapServers — run 'docker-compose up -d' first")

        TopicAdmin.createTopics(cfg)
    }

    @Test
    fun `event produced to Kafka is received by consumer`() = runBlocking {
        val producer = KafkaProducerService(cfg, json)

        val sentEvent = ClickEvent(
            userId      = "user-123",
            timestampMs = System.currentTimeMillis(),
            itemId      = "item-456",
            sessionId   = "session-789"
        )

        // Produce the event first so it's already in the topic
        producer.sendEvent(sentEvent)
        producer.close()

        // Now consume with "earliest" — picks up the event regardless of rebalance timing
        val consumer = KafkaConsumerService(cfg, json)
        var receivedEvent: RecEngineEvent? = null

        val consumerJob = launch {
            consumer.eventFlow(cfg.topicEvents, offsetReset = "earliest").collect { event ->
                receivedEvent = event
                return@collect
            }
        }

        withTimeout(20_000) {
            while (receivedEvent == null) {
                delay(100)
            }
        }

        consumerJob.cancel()

        val received = receivedEvent as ClickEvent
        assertEquals(sentEvent.userId,    received.userId)
        assertEquals(sentEvent.itemId,    received.itemId)
        assertEquals(sentEvent.sessionId, received.sessionId)
    }
}
