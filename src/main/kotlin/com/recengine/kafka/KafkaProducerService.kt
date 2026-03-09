package com.recengine.kafka

import com.recengine.config.KafkaConfig
import com.recengine.model.FeedbackEvent
import com.recengine.model.RecEngineEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import java.util.Properties

private val log = KotlinLogging.logger {}

class KafkaProducerService(private val cfg: KafkaConfig, private val json: Json) {

    private val producer: KafkaProducer<String, String> by lazy {
        KafkaProducer(Properties().apply {
            put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,      cfg.bootstrapServers)
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,   StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.ACKS_CONFIG,                   "all")
            put(ProducerConfig.RETRIES_CONFIG,                "3")
            put(ProducerConfig.COMPRESSION_TYPE_CONFIG,       "lz4")
            put(ProducerConfig.LINGER_MS_CONFIG,              "5")
        })
    }

    suspend fun sendEvent(event: RecEngineEvent, topic: String? = null) = withContext(Dispatchers.IO) {
        val targetTopic = topic ?: cfg.topicEvents
        val record = ProducerRecord(targetTopic, event.userId, json.encodeToString(event))
        try {
            producer.send(record).get()
            log.debug { "Sent ${event::class.simpleName} for user=${event.userId} to $targetTopic" }
        } catch (e: Exception) {
            log.error(e) { "Failed to send event to $targetTopic" }
            throw e
        }
    }

    suspend fun sendFeedback(event: FeedbackEvent) = withContext(Dispatchers.IO) {
        val record = ProducerRecord(cfg.topicFeedback, event.userId, json.encodeToString(event))
        try {
            producer.send(record).get()
            log.debug { "Sent FeedbackEvent for user=${event.userId}" }
        } catch (e: Exception) {
            log.error(e) { "Failed to send feedback event" }
            throw e
        }
    }

    fun close() {
        producer.flush()
        producer.close()
        log.info { "KafkaProducerService closed" }
    }
}
