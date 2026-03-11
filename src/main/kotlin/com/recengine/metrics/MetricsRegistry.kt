package com.recengine.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry

/**
 * Application-wide Micrometer registry backed by Prometheus.
 *
 * All metric definitions live here so they can be shared between the Ktor plugin
 * (which installs the registry) and the code that records measurements.
 */
object MetricsRegistry {

    val prometheus: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    /**
     * Counter for each event type processed by [EventProcessor].
     * Tag: `type` — the simple class name of the [RecEngineEvent] subtype.
     */
    fun eventsProcessedCounter(eventType: String): Counter =
        Counter.builder("recengine.events.processed")
            .description("Number of events processed by the pipeline")
            .tag("type", eventType)
            .register(prometheus)

    /**
     * Timer that measures end-to-end latency of [ScoringEngine.scoreItems].
     * Publishes percentiles at p50 / p95 / p99 for Prometheus histogram.
     */
    val recommendationLatency: Timer =
        Timer.builder("recengine.recommendation.latency")
            .description("End-to-end latency of the recommendation scoring call")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(prometheus)

    /** Counter incremented each time the FM model performs an online update. */
    val fmUpdatesCounter: Counter =
        Counter.builder("recengine.fm.updates")
            .description("Number of FM model weight updates")
            .register(prometheus)
}
