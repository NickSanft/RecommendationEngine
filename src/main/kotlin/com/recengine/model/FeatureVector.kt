package com.recengine.model

import kotlinx.serialization.Serializable

@Serializable
data class FeatureVector(
    val indices: IntArray,
    val values: DoubleArray
) {
    init {
        require(indices.size == values.size) { "Indices and values must have equal length" }
    }

    // IntArray/DoubleArray use reference equality in auto-generated equals/hashCode,
    // so we override them for structural correctness.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FeatureVector) return false
        return indices.contentEquals(other.indices) && values.contentEquals(other.values)
    }

    override fun hashCode(): Int = 31 * indices.contentHashCode() + values.contentHashCode()
}

@Serializable
data class ItemFeatures(
    val itemId: String,
    val categoryIds: List<String>,
    val tags: List<String>,
    val publishedAtMs: Long,
    val popularity: Double,
    val embeddings: List<Float> = emptyList()
)

@Serializable
data class UserProfile(
    val userId: String,
    val preferenceVector: Map<String, Double> = emptyMap(),
    val interactionCount: Long = 0,
    val lastSeenMs: Long = System.currentTimeMillis()
)
