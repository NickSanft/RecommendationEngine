package com.recengine.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class RecEngineEvent {
    abstract val userId: String
    abstract val timestampMs: Long
}

@Serializable
@SerialName("view")
data class ViewEvent(
    override val userId: String,
    override val timestampMs: Long,
    val itemId: String,
    val sessionId: String,
    val dwellSeconds: Double = 0.0,
    val sourcePosition: Int = 0
) : RecEngineEvent()

@Serializable
@SerialName("click")
data class ClickEvent(
    override val userId: String,
    override val timestampMs: Long,
    val itemId: String,
    val sessionId: String,
    val recommendationId: String? = null
) : RecEngineEvent()

@Serializable
@SerialName("purchase")
data class PurchaseEvent(
    override val userId: String,
    override val timestampMs: Long,
    val itemId: String,
    val sessionId: String,
    val revenue: Double,
    val quantity: Int = 1
) : RecEngineEvent()

@Serializable
@SerialName("impression")
data class ImpressionEvent(
    override val userId: String,
    override val timestampMs: Long,
    val itemIds: List<String>,
    val sessionId: String,
    val variantId: String
) : RecEngineEvent()

@Serializable
data class FeedbackEvent(
    val userId: String,
    val itemId: String,
    val label: Double,
    val features: Map<String, Double>,
    val timestampMs: Long = System.currentTimeMillis()
)
