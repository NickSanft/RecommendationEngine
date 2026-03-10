package com.recengine.ml

import com.recengine.config.ScoringWeights
import com.recengine.model.ItemFeatures
import com.recengine.model.ScoreComponents
import com.recengine.model.ScoredItem
import com.recengine.redis.FeatureStore
import com.recengine.redis.SessionStore
import kotlin.math.ln
import kotlin.math.max

/**
 * Composite scoring engine.
 *
 * composite = weights.session    × sessionScore      (decayed session interest)
 *           + weights.content    × contentScore      (FM sigmoid output)
 *           + weights.popularity × popularityScore   (log-normalised view count)
 *           + weights.recency    × recencyScore      (exp decay from publish date)
 *
 * Results are sorted descending by composite score.
 */
class ScoringEngine(
    private val fm: OnlineFM,
    private val sessionStore: SessionStore,
    private val featureStore: FeatureStore,
    private val weights: ScoringWeights
) {
    suspend fun scoreItems(
        userId: String,
        candidateItemIds: List<String>,
        featureBuilder: FeatureVectorBuilder
    ): List<ScoredItem> {
        val sessionVector = sessionStore.getDecayedVector(userId)
        val nowMs         = System.currentTimeMillis()

        return candidateItemIds.mapNotNull { itemId ->
            val itemFeatures = featureStore.getItemFeatures(itemId) ?: return@mapNotNull null
            val itemStats    = featureStore.getItemStats(itemId)
            val fv           = featureBuilder.build(userId, itemId, itemFeatures)

            val sessionScore    = sessionVector[itemId] ?: 0.0
            val contentScore    = fm.score(fv)
            val popularityScore = normalizePopularity(itemStats["views"] ?: 0.0)
            val recencyScore    = recencyDecay(itemFeatures.publishedAtMs, nowMs)

            val composite = weights.session    * sessionScore +
                            weights.content    * contentScore +
                            weights.popularity * popularityScore +
                            weights.recency    * recencyScore

            ScoredItem(
                itemId     = itemId,
                score      = composite,
                components = ScoreComponents(
                    sessionScore    = sessionScore,
                    contentScore    = contentScore,
                    popularityScore = popularityScore,
                    recencyScore    = recencyScore,
                    compositeScore  = composite
                )
            )
        }.sortedByDescending { it.score }
    }

    // log-normalise over a plausible max of 1 million views
    private fun normalizePopularity(views: Double) =
        if (views <= 0) 0.0 else ln(views + 1.0) / ln(1_000_001.0)

    // half-life of 7 days: score halves every week
    private fun recencyDecay(publishedAtMs: Long, nowMs: Long): Double {
        val daysOld = max(0.0, (nowMs - publishedAtMs) / 86_400_000.0)
        return kotlin.math.exp(-ln(2.0) / 7.0 * daysOld)
    }
}
