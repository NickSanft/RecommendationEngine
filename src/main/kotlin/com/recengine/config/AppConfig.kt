package com.recengine.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

data class KafkaConfig(
    val bootstrapServers: String,
    val groupId: String,
    val topicEvents: String,
    val topicUpdates: String,
    val topicFeedback: String,
    val pollTimeoutMs: Long,
    val maxPollRecords: Int
)

data class RedisConfig(
    val uri: String,
    val sessionTtlSeconds: Long,
    val featureTtlSeconds: Long
)

data class FmConfig(
    val numFactors: Int,
    val learningRate: Double,
    val regularization: Double,
    val numFeatures: Int
)

data class BanditConfig(
    val epsilon: Double,
    val ucbAlpha: Double,
    val windowSize: Int
)

data class ScoringWeights(
    val session: Double,
    val content: Double,
    val popularity: Double,
    val recency: Double
)

data class ModelConfig(
    val fm: FmConfig,
    val bandit: BanditConfig,
    val scoring: ScoringWeights
)

data class AbConfig(
    val salt: String,
    val variants: List<String>
)

data class AppConfig(
    val kafka: KafkaConfig,
    val redis: RedisConfig,
    val model: ModelConfig,
    val ab: AbConfig
) {
    companion object {
        fun load(config: Config = ConfigFactory.load()): AppConfig {
            val rc = config.getConfig("recengine")
            return AppConfig(
                kafka = rc.getConfig("kafka").let { k ->
                    KafkaConfig(
                        bootstrapServers = k.getString("bootstrapServers"),
                        groupId          = k.getString("groupId"),
                        topicEvents      = k.getString("topics.events"),
                        topicUpdates     = k.getString("topics.updates"),
                        topicFeedback    = k.getString("topics.feedback"),
                        pollTimeoutMs    = k.getLong("pollTimeoutMs"),
                        maxPollRecords   = k.getInt("maxPollRecords")
                    )
                },
                redis = rc.getConfig("redis").let { r ->
                    RedisConfig(
                        uri               = r.getString("uri"),
                        sessionTtlSeconds = r.getLong("sessionTtlSeconds"),
                        featureTtlSeconds = r.getLong("featureTtlSeconds")
                    )
                },
                model = rc.getConfig("model").let { m ->
                    ModelConfig(
                        fm = m.getConfig("fm").let { f ->
                            FmConfig(
                                numFactors     = f.getInt("numFactors"),
                                learningRate   = f.getDouble("learningRate"),
                                regularization = f.getDouble("regularization"),
                                numFeatures    = f.getInt("numFeatures")
                            )
                        },
                        bandit = m.getConfig("bandit").let { b ->
                            BanditConfig(
                                epsilon    = b.getDouble("epsilon"),
                                ucbAlpha   = b.getDouble("ucbAlpha"),
                                windowSize = b.getInt("windowSize")
                            )
                        },
                        scoring = m.getConfig("scoring").let { s ->
                            ScoringWeights(
                                session    = s.getDouble("sessionWeight"),
                                content    = s.getDouble("contentWeight"),
                                popularity = s.getDouble("popularityWeight"),
                                recency    = s.getDouble("recencyWeight")
                            )
                        }
                    )
                },
                ab = rc.getConfig("ab").let { a ->
                    AbConfig(
                        salt     = a.getString("salt"),
                        variants = a.getStringList("variants")
                    )
                }
            )
        }
    }
}
