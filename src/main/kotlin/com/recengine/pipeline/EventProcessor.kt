package com.recengine.pipeline

import com.recengine.kafka.KafkaConsumerService
import com.recengine.metrics.MetricsRegistry
import com.recengine.ml.FeatureVectorBuilder
import com.recengine.ml.OnlineFM
import com.recengine.model.ClickEvent
import com.recengine.model.ImpressionEvent
import com.recengine.model.PurchaseEvent
import com.recengine.model.RecEngineEvent
import com.recengine.model.ViewEvent
import com.recengine.redis.FeatureStore
import com.recengine.redis.SessionStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val log = KotlinLogging.logger {}

/**
 * Central stream processor. Launched once at application startup and runs until [stop] is called.
 *
 * Event weights:
 *   View     → dwell-time-scaled weight in [0, 1]  (30 s dwell = weight 1.0)
 *   Click    → 0.7  (strong positive signal, triggers FM learning)
 *   Purchase → 1.0  (strongest signal, triggers FM learning)
 *   Impression → logged only (no session update; used for A/B accounting later)
 */
class EventProcessor(
    private val consumer: KafkaConsumerService,
    private val sessionStore: SessionStore,
    private val featureStore: FeatureStore,
    private val fm: OnlineFM,
    private val featureBuilder: FeatureVectorBuilder,
    private val kafkaTopicEvents: String,
    private val kafkaTopicFeedback: String,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("EventProcessor")
    )

    fun start() {
        scope.launch {
            consumer.eventFlow(kafkaTopicEvents)
                .catch { e -> log.error(e) { "EventProcessor stream error — restarting" } }
                .collect { event -> handleEvent(event) }
        }
        log.info { "EventProcessor started — consuming $kafkaTopicEvents" }
    }

    fun stop() {
        scope.cancel()
        log.info { "EventProcessor stopped" }
    }

    internal suspend fun handleEvent(event: RecEngineEvent) = try {
        when (event) {
            is ViewEvent -> {
                val weight = (event.dwellSeconds / 30.0).coerceIn(0.0, 1.0)
                sessionStore.recordInteraction(event.userId, event.itemId, weight)
                featureStore.incrementItemStat(event.itemId, "views")
            }

            is ClickEvent -> {
                sessionStore.recordInteraction(event.userId, event.itemId, 0.7)
                featureStore.incrementItemStat(event.itemId, "clicks")
                featureStore.getItemFeatures(event.itemId)?.let { features ->
                    withContext(Dispatchers.Default) {
                        fm.learn(featureBuilder.build(event.userId, event.itemId, features), 1.0)
                    }
                }
            }

            is PurchaseEvent -> {
                sessionStore.recordInteraction(event.userId, event.itemId, 1.0)
                featureStore.incrementItemStat(event.itemId, "purchases")
                featureStore.getItemFeatures(event.itemId)?.let { features ->
                    withContext(Dispatchers.Default) {
                        fm.learn(featureBuilder.build(event.userId, event.itemId, features), 1.0)
                    }
                }
            }

            is ImpressionEvent -> {
                featureStore.incrementPopularity(event.userId, "hourly")
                log.debug { "Impression logged for user=${event.userId} items=${event.itemIds.size}" }
            }
        }
        MetricsRegistry.eventsProcessedCounter(event::class.simpleName ?: "Unknown").increment()
    } catch (e: Exception) {
        log.warn(e) { "Error handling ${event::class.simpleName} for user=${event.userId}" }
    }
}
