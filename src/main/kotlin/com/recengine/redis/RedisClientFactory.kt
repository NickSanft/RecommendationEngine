package com.recengine.redis

import com.recengine.config.RedisConfig
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands

class RedisClientFactory(private val config: RedisConfig) {

    private val client: RedisClient = RedisClient.create(RedisURI.create(config.uri))

    // Single shared connection — coroutines() is just an adapter, not a new TCP connection
    private val connection: StatefulRedisConnection<String, String> by lazy { client.connect() }

    fun coroutineCommands(): RedisCoroutinesCommands<String, String> = connection.coroutines()

    fun close() {
        runCatching { connection.close() }
        runCatching { client.shutdown() }
    }
}
