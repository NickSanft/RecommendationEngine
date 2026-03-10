package com.recengine.ml

import com.recengine.model.FeatureVector
import com.recengine.model.ItemFeatures

/**
 * Builds sparse feature vectors for user-item pairs.
 *
 * Features are hashed into [0, numFeatures) using signed-safe floorMod so
 * negative hashCodes always map to valid array indices.
 * Collisions are handled by summing values at the same bucket.
 * Returned vectors have indices sorted ascending (required by OnlineFM).
 */
class FeatureVectorBuilder(private val numFeatures: Int) {

    fun build(userId: String, itemId: String, item: ItemFeatures): FeatureVector {
        val features = mutableMapOf<Int, Double>()

        fun hash(name: String, value: Double = 1.0) {
            val idx = Math.floorMod(name.hashCode(), numFeatures)
            features[idx] = features.getOrDefault(idx, 0.0) + value
        }

        hash("user:$userId")
        hash("item:$itemId")
        item.categoryIds.forEach { hash("category:$it") }
        item.tags.forEach       { hash("tag:$it") }
        hash("popularity", item.popularity)

        val sorted = features.entries.sortedBy { it.key }
        return FeatureVector(
            indices = sorted.map { it.key }.toIntArray(),
            values  = sorted.map { it.value }.toDoubleArray()
        )
    }
}
