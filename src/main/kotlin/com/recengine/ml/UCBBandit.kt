package com.recengine.ml

import com.recengine.config.BanditConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Upper Confidence Bound bandit for A/B arm selection.
 *
 * Selection policy:
 *   - Unvisited arms always get selected first (score = MAX_VALUE).
 *   - Otherwise: score = mean_reward + ucbAlpha × √(ln(t+1) / pulls)
 *
 * Thread-safe: ConcurrentHashMap + AtomicLong.
 */
class UCBBandit(private val cfg: BanditConfig) {

    data class ArmStats(val totalReward: Double = 0.0, val pullCount: Long = 0L)

    private val stats      = ConcurrentHashMap<String, ArmStats>()
    private val totalPulls = AtomicLong(0)

    fun select(arms: List<String>): String {
        require(arms.isNotEmpty()) { "Arms list must not be empty" }
        val t = totalPulls.get()
        return arms.maxByOrNull { arm ->
            val s = stats.getOrDefault(arm, ArmStats())
            if (s.pullCount == 0L) Double.MAX_VALUE
            else s.totalReward / s.pullCount + cfg.ucbAlpha * sqrt(ln(t + 1.0) / s.pullCount)
        } ?: arms.first()
    }

    fun update(arm: String, reward: Double) {
        stats.merge(arm, ArmStats(reward, 1L)) { old, inc ->
            ArmStats(old.totalReward + inc.totalReward, old.pullCount + inc.pullCount)
        }
        totalPulls.incrementAndGet()
    }

    fun totalPulls(): Long = totalPulls.get()

    fun meanReward(arm: String): Double {
        val s = stats[arm] ?: return 0.0
        return if (s.pullCount == 0L) 0.0 else s.totalReward / s.pullCount
    }
}
