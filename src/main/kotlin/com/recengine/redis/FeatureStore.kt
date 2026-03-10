package com.recengine.redis

import com.recengine.model.ItemFeatures
import com.recengine.model.UserProfile
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FeatureStore(
    private val redis: RedisCoroutinesCommands<String, String>,
    private val json: Json
) {
    suspend fun getItemFeatures(itemId: String): ItemFeatures? =
        redis.get("item:$itemId:features")?.let { json.decodeFromString(it) }

    suspend fun setItemFeatures(item: ItemFeatures) {
        redis.set("item:${item.itemId}:features", json.encodeToString(item))
    }

    suspend fun getItemStats(itemId: String): Map<String, Double> =
        redis.hgetall("item:$itemId:stats")
            .toList()
            .filter { it.hasValue() }
            .associate { kv -> kv.getKey() to (kv.getValue().toDoubleOrNull() ?: 0.0) }

    suspend fun incrementItemStat(itemId: String, field: String, by: Long = 1L) {
        redis.hincrby("item:$itemId:stats", field, by)
    }

    suspend fun getUserProfile(userId: String): UserProfile? =
        redis.get("user:$userId:profile")?.let { json.decodeFromString(it) }

    suspend fun setUserProfile(profile: UserProfile) {
        redis.set("user:${profile.userId}:profile", json.encodeToString(profile))
    }

    suspend fun getPopularItems(window: String = "hourly", limit: Long = 100): List<String> =
        redis.zrevrange("popularity:$window", 0, limit - 1).toList()

    suspend fun incrementPopularity(itemId: String, window: String = "hourly", by: Double = 1.0) {
        redis.zincrby("popularity:$window", by, itemId)
    }
}
