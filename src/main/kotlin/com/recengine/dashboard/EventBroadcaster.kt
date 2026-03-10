package com.recengine.dashboard

import com.recengine.config.KafkaConfig
import com.recengine.kafka.KafkaConsumerService
import com.recengine.model.RecEngineEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val log = KotlinLogging.logger {}

/**
 * Bridges KafkaConsumerService → SharedFlow so any number of SSE clients
 * can subscribe without each needing its own Kafka consumer.
 *
 * replay = 50 means a newly connected browser immediately receives the last
 * 50 events without waiting for the next Kafka poll.
 */
class EventBroadcaster(
    private val consumerService: KafkaConsumerService,
    private val kafkaConfig: KafkaConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _events = MutableSharedFlow<RecEngineEvent>(replay = 50, extraBufferCapacity = 1_000)
    val events: SharedFlow<RecEngineEvent> = _events.asSharedFlow()

    /** Call once at application startup (e.g. from the Ktor Application.module()). */
    fun start() {
        scope.launch {
            log.info { "EventBroadcaster starting — consuming from ${kafkaConfig.topicEvents}" }
            consumerService
                .eventFlow(topic = kafkaConfig.topicEvents, groupIdSuffix = "-dashboard", offsetReset = "latest")
                .collect { event ->
                    _events.emit(event)
                }
        }
    }
}