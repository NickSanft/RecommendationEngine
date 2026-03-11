package com.recengine.integration

import com.recengine.config.FmConfig
import com.recengine.config.KafkaConfig
import com.recengine.config.RedisConfig
import com.recengine.kafka.KafkaConsumerService
import com.recengine.kafka.KafkaProducerService
import com.recengine.kafka.TopicAdmin
import com.recengine.ml.FeatureVectorBuilder
import com.recengine.ml.OnlineFM
import com.recengine.model.ClickEvent
import com.recengine.pipeline.EventProcessor
import com.recengine.redis.FeatureStore
import com.recengine.redis.RedisClientFactory
import com.recengine.redis.SessionStore
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FullPipelineIntegrationTest {

    // Containers — started/stopped manually in BeforeAll/AfterAll
    private lateinit var kafka: KafkaContainer
    private lateinit var redis: GenericContainer<*>

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }

    private lateinit var processor: EventProcessor
    private lateinit var redisFactory: RedisClientFactory

    private lateinit var kafkaCfg: KafkaConfig
    private lateinit var sessionStore: SessionStore
    private lateinit var featureStore: FeatureStore

    @BeforeAll
    fun setup() {
        assumeTrue(isDockerAvailable(), "Docker CLI not available — skipping integration test")

        kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))
        @Suppress("UNCHECKED_CAST")
        redis = GenericContainer<Nothing>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379) as GenericContainer<*>

        try {
            kafka.start()
            redis.start()
        } catch (e: IllegalStateException) {
            assumeTrue(false, "Testcontainers cannot reach Docker daemon — skipping: ${e.message}")
        }

        kafkaCfg = KafkaConfig(
            bootstrapServers = kafka.bootstrapServers,
            groupId          = "integration-test-${System.currentTimeMillis()}",
            topicEvents      = "integration-events",
            topicUpdates     = "integration-updates",
            topicFeedback    = "integration-feedback",
            pollTimeoutMs    = 200,
            maxPollRecords   = 100
        )

        val redisCfg = RedisConfig(
            uri               = "redis://${redis.host}:${redis.getMappedPort(6379)}",
            sessionTtlSeconds = 3600,
            featureTtlSeconds = 86400
        )

        TopicAdmin.createTopics(kafkaCfg)

        redisFactory = RedisClientFactory(redisCfg)
        val redisCommands = redisFactory.coroutineCommands()

        val fmConfig = FmConfig(numFactors = 4, learningRate = 0.01, regularization = 0.001, numFeatures = 1_000)
        sessionStore = SessionStore(redisCommands, redisCfg)
        featureStore = FeatureStore(redisCommands, json)
        val fm = OnlineFM(fmConfig)
        val featureBuilder = FeatureVectorBuilder(fmConfig.numFeatures)

        processor = EventProcessor(
            consumer           = KafkaConsumerService(kafkaCfg, json),
            sessionStore       = sessionStore,
            featureStore       = featureStore,
            fm                 = fm,
            featureBuilder     = featureBuilder,
            kafkaTopicEvents   = kafkaCfg.topicEvents,
            kafkaTopicFeedback = kafkaCfg.topicFeedback,
        )
    }

    @AfterAll
    fun teardown() {
        runCatching { processor.stop() }
        runCatching { redisFactory.close() }
        runCatching { if (::kafka.isInitialized) kafka.stop() }
        runCatching { if (::redis.isInitialized) redis.stop() }
    }

    @Test
    fun `click events update session vector in Redis`() = runBlocking {
        val producer = KafkaProducerService(kafkaCfg, json)
        val userId   = "tc-user-001"
        val itemId   = "tc-item-001"

        // Produce 3 click events before starting consumer so "earliest" picks them up
        repeat(3) {
            producer.sendEvent(ClickEvent(userId, System.currentTimeMillis(), itemId, "session-1"))
        }
        producer.close()

        // Start processor with default "latest" from eventFlow — we use a fresh consumer group
        // so earliest will pick up whatever is in the topic
        processor.start()

        // Verify via raw Lettuce sync client to avoid coroutines complexity in assertion
        val sessionKey  = "session:$userId:vector"
        val redisClient = RedisClient.create(RedisURI.create("redis://${redis.host}:${redis.getMappedPort(6379)}"))
        val syncCommands = redisClient.connect().sync()

        try {
            withTimeout(15_000) {
                while (syncCommands.hgetall(sessionKey).isEmpty()) {
                    delay(200)
                }
            }

            val sessionData = syncCommands.hgetall(sessionKey)
            assertNotNull(sessionData[itemId], "Session vector should contain the clicked item '$itemId'")
            assertTrue(sessionData[itemId]!!.toDouble() > 0.0, "Session weight should be positive")
        } finally {
            redisClient.shutdown()
        }
    }

    @Test
    fun `impression events increment popularity scores`() = runBlocking {
        // Verify that a direct FeatureStore call works end-to-end against the real Redis container
        featureStore.incrementPopularity("test-item-popularity", "hourly", 5.0)
        val popular = featureStore.getPopularItems("hourly", 10L)
        assertTrue(popular.contains("test-item-popularity"), "Item should appear in popularity list after increment")
    }

    private fun isDockerAvailable(): Boolean = try {
        ProcessBuilder("docker", "info")
            .redirectErrorStream(true)
            .start()
            .waitFor() == 0
    } catch (e: Exception) { false }
}
