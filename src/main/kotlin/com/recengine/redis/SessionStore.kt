package com.recengine.redis

import com.recengine.config.RedisConfig
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import kotlinx.coroutines.flow.toList
import kotlin.math.exp
import kotlin.math.ln

class SessionStore(
    private val redis: RedisCoroutinesCommands<String, String>,
    private val config: RedisConfig
) {
    private fun vectorKey(userId: String) = "session:$userId:vector"
    private fun metaKey(userId: String)   = "session:$userId:metadata"

    /**
     * Returns the session interest vector for [userId] with exponential decay applied.
     *
     * decay(t) = exp(-λ × hoursElapsed),  λ = ln(2) / halfLifeHours
     *
     * At the default 4-hour half-life, a weight recorded 4 hours ago is worth
     * 50% of a freshly recorded weight.
     */
    suspend fun getDecayedVector(
        userId: String,
        halfLifeHours: Double = 4.0
    ): Map<String, Double> {
        val rawEntries = redis.hgetall(vectorKey(userId)).toList()
        if (rawEntries.isEmpty()) return emptyMap()

        val lastSeenMs = redis.hget(metaKey(userId), "lastSeen")
            ?.toLongOrNull() ?: return emptyMap()

        val hoursElapsed = (System.currentTimeMillis() - lastSeenMs) / 3_600_000.0
        val lambda       = ln(2.0) / halfLifeHours
        val decayFactor  = exp(-lambda * hoursElapsed)

        return rawEntries
            .filter { it.hasValue() }
            .associate { kv -> kv.getKey() to (kv.getValue().toDoubleOrNull() ?: 0.0) * decayFactor }
    }

    /**
     * Records a user–item interaction using a weighted moving average.
     *
     * new_weight = alpha × eventWeight + (1 − alpha) × old_weight
     */
    suspend fun recordInteraction(
        userId: String,
        itemId: String,
        eventWeight: Double,
        alpha: Double = 0.3
    ) {
        val key     = vectorKey(userId)
        val current = redis.hget(key, itemId)?.toDoubleOrNull() ?: 0.0
        val updated = alpha * eventWeight + (1.0 - alpha) * current

        redis.hset(key, itemId, updated.toString())
        redis.hset(metaKey(userId), "lastSeen", System.currentTimeMillis().toString())
        redis.expire(key, config.sessionTtlSeconds)
        redis.expire(metaKey(userId), config.sessionTtlSeconds)
    }

    /**
     * Re-writes the session vector with the current decay applied.
     * Called periodically by [com.recengine.pipeline.SessionDecayJob].
     */
    suspend fun applyDecayInPlace(userId: String, halfLifeHours: Double = 4.0) {
        val decayed = getDecayedVector(userId, halfLifeHours)
        if (decayed.isEmpty()) return
        redis.hset(vectorKey(userId), decayed.mapValues { it.value.toString() })
        redis.hset(metaKey(userId), "lastSeen", System.currentTimeMillis().toString())
    }
}
