package com.recengine.kafka

import com.recengine.config.KafkaConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.errors.TopicExistsException
import java.util.Properties
import java.util.concurrent.ExecutionException

private val log = KotlinLogging.logger {}

data class TopicSpec(val name: String, val partitions: Int, val replicationFactor: Short)

object TopicAdmin {

    private val topics = listOf(
        TopicSpec("user-events",     partitions = 12, replicationFactor = 1),
        TopicSpec("model-updates",   partitions = 3,  replicationFactor = 1),
        TopicSpec("feedback-events", partitions = 6,  replicationFactor = 1)
    )

    fun createTopics(cfg: KafkaConfig) {
        val props = Properties().apply {
            put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, cfg.bootstrapServers)
        }

        AdminClient.create(props).use { admin ->
            val newTopics = topics.map { spec ->
                NewTopic(spec.name, spec.partitions, spec.replicationFactor)
            }

            val result = admin.createTopics(newTopics)
            topics.forEach { spec ->
                try {
                    result.values()[spec.name]?.get()
                    log.info { "Created topic: ${spec.name} (partitions=${spec.partitions})" }
                } catch (e: ExecutionException) {
                    when (e.cause) {
                        is TopicExistsException -> log.info { "Topic already exists: ${spec.name}" }
                        else -> log.error(e) { "Failed to create topic: ${spec.name}" }
                    }
                }
            }
        }
    }
}

/** Run directly to create all Kafka topics: `./gradlew run --args="--create-topics"` */
fun main() {
    val config = com.recengine.config.AppConfig.load()
    log.info { "Connecting to Kafka at ${config.kafka.bootstrapServers}" }
    TopicAdmin.createTopics(config.kafka)
    log.info { "Topic setup complete" }
}
