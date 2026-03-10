package com.recengine.ml

import com.recengine.config.BanditConfig
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Epsilon-greedy bandit for A/B arm selection.
 *
 * With probability epsilon: explore (pick a random arm).
 * Otherwise: exploit (pick the arm with the highest mean reward).
 *
 * Thread-safe: ConcurrentHashMap with atomic merge.
 */
class EpsilonGreedyBandit(private val cfg: BanditConfig) {

    // arm → (totalReward, pullCount)
    private val stats = ConcurrentHashMap<String, Pair<Double, Long>>()

    fun select(arms: List<String>): String {
        require(arms.isNotEmpty()) { "Arms list must not be empty" }
        return if (Random.nextDouble() < cfg.epsilon) {
            arms.random()
        } else {
            arms.maxByOrNull { arm ->
                val (reward, count) = stats.getOrDefault(arm, 0.0 to 0L)
                if (count == 0L) Double.MAX_VALUE else reward / count
            } ?: arms.first()
        }
    }

    fun update(arm: String, reward: Double) {
        stats.merge(arm, reward to 1L) { old, _ ->
            (old.first + reward) to (old.second + 1L)
        }
    }

    fun meanReward(arm: String): Double {
        val (reward, count) = stats.getOrDefault(arm, 0.0 to 0L)
        return if (count == 0L) 0.0 else reward / count
    }

    fun pullCount(arm: String): Long = stats[arm]?.second ?: 0L
}
