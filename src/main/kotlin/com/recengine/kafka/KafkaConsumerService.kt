package com.recengine.kafka

import com.recengine.config.KafkaConfig
import com.recengine.model.RecEngineEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.Properties

private val log = KotlinLogging.logger {}

class KafkaConsumerService(private val cfg: KafkaConfig, private val json: Json) {

    private fun buildProperties(groupIdSuffix: String = "", offsetReset: String = "latest") = Properties().apply {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,          cfg.bootstrapServers)
        put(ConsumerConfig.GROUP_ID_CONFIG,                   cfg.groupId + groupIdSuffix)
        put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,     StringDeserializer::class.java.name)
        put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,   StringDeserializer::class.java.name)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,          offsetReset)
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,         "false")
        put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,           cfg.maxPollRecords.toString())
        put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,         "30000")
    }

    /**
     * Returns a cold Flow of deserialized events from the given Kafka topic.
     *
     * The Flow runs on Dispatchers.IO since KafkaConsumer.poll() is a blocking call.
     * Collect it inside a coroutine scope tied to your application lifecycle:
     *
     *   scope.launch {
     *       consumer.eventFlow("user-events").collect { event -> handle(event) }
     *   }
     *
     * The consumer is cleanly closed when the collecting coroutine is cancelled.
     */
    fun eventFlow(topic: String, groupIdSuffix: String = "", offsetReset: String = "latest"): Flow<RecEngineEvent> = flow {
        val consumer = KafkaConsumer<String, String>(buildProperties(groupIdSuffix, offsetReset))
        consumer.subscribe(listOf(topic))
        log.info { "Subscribed to Kafka topic: $topic (group=${cfg.groupId}$groupIdSuffix)" }
        try {
            while (currentCoroutineContext().isActive) {
                val records = consumer.poll(Duration.ofMillis(cfg.pollTimeoutMs))
                for (record in records) {
                    try {
                        emit(json.decodeFromString<RecEngineEvent>(record.value()))
                    } catch (e: Exception) {
                        log.warn(e) { "Failed to deserialize record from $topic offset=${record.offset()}" }
                    }
                }
                if (!records.isEmpty) {
                    consumer.commitAsync { offsets, exception ->
                        if (exception != null) log.warn(exception) { "Async commit failed: $offsets" }
                    }
                }
            }
        } finally {
            runCatching { consumer.close() }
            log.info { "Kafka consumer for topic=$topic closed" }
        }
    }.flowOn(Dispatchers.IO)
}
