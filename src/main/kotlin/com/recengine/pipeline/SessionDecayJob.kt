package com.recengine.pipeline

import com.recengine.redis.SessionStore
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger {}

/**
 * Background job that runs every [intervalMs] ms.
 *
 * Scans all `session:*:vector` keys with Redis SCAN (non-blocking, cursor-based)
 * and applies in-place exponential decay via [SessionStore.applyDecayInPlace].
 * This ensures stale session weights don't artificially inflate recommendation scores.
 */
class SessionDecayJob(
    private val redis: RedisCoroutinesCommands<String, String>,
    private val sessionStore: SessionStore,
    private val intervalMs: Long = 15 * 60 * 1_000L   // 15 minutes
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("SessionDecayJob")
    )

    fun start() {
        scope.launch {
            while (isActive) {
                delay(intervalMs)
                runDecay()
            }
        }
        log.info { "SessionDecayJob started (interval=${intervalMs}ms)" }
    }

    fun stop() {
        scope.cancel()
        log.info { "SessionDecayJob stopped" }
    }

    internal suspend fun runDecay() = try {
        val scanArgs = ScanArgs.Builder.matches("session:*:vector").limit(500L)
        var cursor: ScanCursor = ScanCursor.INITIAL
        var swept = 0

        do {
            val result = redis.scan(cursor, scanArgs) ?: break
            result.keys.forEach { key ->
                val userId = key.removePrefix("session:").removeSuffix(":vector")
                sessionStore.applyDecayInPlace(userId)
                swept++
            }
            cursor = result
        } while (!result.isFinished)

        log.debug { "Session decay sweep complete — $swept keys processed" }
    } catch (e: Exception) {
        log.error(e) { "Session decay sweep failed" }
    }
}
