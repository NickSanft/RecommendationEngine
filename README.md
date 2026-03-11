# Real-Time Streaming Recommendation Engine

A production-grade, end-to-end recommendation system built entirely on the JVM. Clickstream events flow from a browser through Kafka into a Kotlin-native stream processing pipeline, where an online Factorisation Machine learns in real time and serves personalised recommendations within milliseconds — all without a Python runtime or any external ML service.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│  Browser / Demo Shop (/shop)                                             │
│  Fires click · view · purchase · impression events via fetch()           │
└────────────────────────────┬─────────────────────────────────────────────┘
                             │ POST /api/v1/events/*
                             ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  Ktor HTTP Server  :8080   (Netty engine, Kotlin coroutines)            │
│  FeedbackRoutes · RecommendationRoutes · StatsRoutes · DashboardRoutes  │
└────────┬────────────────────────────────────────────┬────────────────────┘
         │ kafka-clients                              │ SSE  /events/stream
         ▼                                           ▼
┌──────────────────┐                    ┌─────────────────────────────────┐
│  Kafka           │                    │  EventBroadcaster               │
│  user-events     │                    │  SharedFlow(replay=50)          │
│  feedback-events │                    │  → dashboard.html Live Feed     │
└────────┬─────────┘                    └─────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  EventProcessor  (supervised CoroutineScope, Kotlin Flow consumer)       │
│  · Updates session interest vectors (exponential decay, t½ = 4 h)        │
│  · Increments item popularity scores (hourly + daily windows)             │
│  · Calls OnlineFM.learn() for each user–item interaction                  │
└────────┬─────────────────────────────────────────┬────────────────────────┘
         │                                         │
         ▼                                         ▼
┌──────────────────────────┐           ┌──────────────────────────────────┐
│  Redis  (Lettuce)        │           │  OnlineFM                        │
│  session:{u}:vector      │           │  k=16 latent factors             │
│  item:{i}:features       │           │  AdaGrad SGD  lr=0.01  reg=0.001 │
│  popularity:hourly/daily │           │  Thread-safe AtomicDoubleArray   │
│  ab:{u}:variant (TTL 7d) │           └──────────────────────────────────┘
│  SessionDecayJob 15 min  │
└────────┬─────────────────┘
         │
         ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  ScoringEngine                                                            │
│  composite = 0.40 × session  +  0.35 × FM content  +                    │
│              0.15 × popularity  +  0.10 × recency                        │
└────────┬─────────────────────────────────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  AbAssigner  (SHA-256 deterministic)                                     │
│  Variants: control · fm_v1 · bandit_ucb                                  │
└────────┬─────────────────────────────────────────────────────────────────┘
         │
         ▼
    GET /api/v1/recommendations/{userId}
```

---

## Feature Highlights

### Streaming Pipeline
- **Kafka** ingests four event types: `ClickEvent`, `ViewEvent`, `PurchaseEvent`, `ImpressionEvent`
- **EventProcessor** consumes events via a Kotlin `Flow`, updating Redis state and training the FM model on each event with no batch delay
- **SessionDecayJob** sweeps Redis every 15 minutes applying exponential decay (half-life 4 hours) to session interest vectors so old interactions naturally fade

### Online Learning
- **OnlineFM** — a custom Kotlin implementation of a degree-2 Factorisation Machine trained with AdaGrad SGD
- Learns from every user interaction without retraining from scratch
- **FeatureVectorBuilder** uses the hashing trick to map user IDs, item IDs, categories, and tags into a fixed-dimension feature space with no vocabulary management
- Running loss tracked via EWMA and exposed through the stats API

### Scoring & Personalisation
- **ScoringEngine** assembles a composite score from four components:
  - *Session score* — dot product of decayed session vector with item's category weights
  - *Content score* — FM sigmoid output for the user–item feature vector
  - *Popularity score* — log-normalised view count from Redis sorted sets
  - *Recency score* — exponential decay from item publish date (half-life 7 days)
- Results ranked descending and returned as `ScoredItem` with per-component breakdown

### A/B Testing
- **AbAssigner** assigns users to experiment variants deterministically via SHA-256 hash mod bucket count — the same user always gets the same variant across sessions
- Assignments cached in Redis with a 7-day TTL
- Variant tag included in every `RecommendationResponse` for downstream attribution

### Real-Time Dashboard
Four-tab browser UI at `/` served over SSE — no page refreshes, no polling:

| Tab | Content |
|---|---|
| **Live Feed** | Streaming event cards with expandable JSON payloads, type filters, pause/resume, and seed controls |
| **Architecture** | Live system diagram with colour-coded health dots (green/amber/red) polled from `/health/*` every 10 s |
| **Pipeline Stats** | Throughput sparkline (events/sec), cumulative event-type bar chart, Redis key count, uptime |
| **Model** | FM update count, running loss sparkline, last-trained timestamp, recommendation latency p50/p95/p99 |

### Demo Shopping Page
Amazon-style storefront at `/shop`:
- 50 synthetic products across 6 categories (electronics, books, sports, home, clothing, toys)
- 20 simulated users (Alice → Tina) selectable via dropdown
- *Recommended for You* section — live recommendations from the pipeline, refreshed every 30 s
- *Trending Now* section — top items from the hourly popularity sorted set
- *All Products* grid with category filter bar
- Every interaction fires a real event through the full pipeline:
  - `ImpressionEvent` on scroll-into-view (IntersectionObserver)
  - `ClickEvent` on "View Details"
  - `ViewEvent` on modal close with measured dwell time
  - `PurchaseEvent` on "Buy Now"
- Item detail modal with live dwell-time timer and simulated star ratings
- A/B variant badge shown in header; one-click seed button populates Redis when starting fresh

### Observability
- **Micrometer + Prometheus** registry exposed at `GET /metrics`
- `recengine.events.processed` counter tagged by event type
- `recengine.recommendation.latency` histogram with p50/p95/p99 percentiles
- FM update count and running loss exposed via `GET /api/v1/stats`
- Per-component health checks: `GET /health`, `/health/kafka`, `/health/redis`

---

## Tech Stack

| Concern | Library / Version |
|---|---|
| Language | Kotlin 2.1.20 |
| Web framework | Ktor 3.1.2 (Netty engine, coroutines-native) |
| Build tool | Gradle 9.3.0 with Kotlin DSL |
| JVM target | 23 (Amazon Corretto 25) |
| Message broker | Apache Kafka via `kafka-clients 3.7` |
| Cache / state | Redis via Lettuce coroutines |
| Serialization | `kotlinx.serialization` + sealed class discriminator |
| Configuration | Typesafe Config (HOCON) |
| Metrics | Micrometer + Prometheus registry |
| Logging | kotlin-logging + Logback |
| Testing | JUnit 5 + MockK + TestContainers |
| Frontend | Vanilla HTML/CSS/JS + Chart.js 4 (CDN) |

---

## Quick Start

### Prerequisites
- Docker (for Kafka + Redis)
- JDK 23+ (Amazon Corretto 25 recommended)
- Gradle wrapper included (`./gradlew`)

### 1. Start infrastructure

```bash
docker-compose up -d
```

This starts:
- **Kafka** (Bitnami KRaft, no ZooKeeper) on port `9092`
- **Redis** on port `6379`

### 2. Run the server

```bash
./gradlew run
```

Server starts on `http://localhost:8080`.

### 3. Seed data and explore

Open `http://localhost:8080/shop` in a browser. Click **"Seed Items + Events"** in the banner — this writes item features and initial popularity scores to Redis, then seeds 100 synthetic events through Kafka. Within a few seconds both *Recommended for You* and *Trending Now* sections populate.

Open `http://localhost:8080` in a second tab to watch events arrive in the Live Feed as you browse the shop.

---

## API Reference

### Events (ingest)

| Method | Path | Body |
|---|---|---|
| `POST` | `/api/v1/events/click` | `ClickEvent` |
| `POST` | `/api/v1/events/view` | `ViewEvent` |
| `POST` | `/api/v1/events/purchase` | `PurchaseEvent` |
| `POST` | `/api/v1/events/impression` | `ImpressionEvent` |
| `POST` | `/api/v1/events/feedback` | `FeedbackEvent` (direct FM label) |
| `POST` | `/api/v1/events/batch` | `List<RecEngineEvent>` (max 100) |

### Recommendations

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/v1/recommendations/{userId}` | Query params: `limit` (1–100, default 20), `session_id` |
| `POST` | `/api/v1/recommendations` | Body: `RecommendationRequest` with `excludeItemIds` support |
| `GET` | `/api/v1/trending` | Query param: `window=hourly\|daily` |

**Example response:**
```json
{
  "userId": "user-001",
  "sessionId": "shop-user-001-1720000000000",
  "recommendationId": "a3f2...",
  "variantId": "fm_v1",
  "items": [
    {
      "itemId": "item-007",
      "score": 0.743,
      "components": {
        "sessionScore": 0.61,
        "contentScore": 0.82,
        "popularityScore": 0.34,
        "recencyScore": 0.91,
        "compositeScore": 0.743
      }
    }
  ]
}
```

### Stats & Observability

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/v1/stats` | `PipelineStats` — event counters, FM state, latency percentiles, Redis key count, uptime |
| `GET` | `/api/v1/admin/model/metrics` | `ModelMetrics` — FM updates, loss, variant distribution |
| `GET` | `/health` | `{"status":"ok","uptimeMs":...}` |
| `GET` | `/health/kafka` | Kafka connectivity |
| `GET` | `/health/redis` | Redis connectivity |
| `GET` | `/metrics` | Prometheus text exposition format |

### UI

| Method | Path | Notes |
|---|---|---|
| `GET` | `/` | Dashboard (Live Feed · Architecture · Pipeline Stats · Model) |
| `GET` | `/shop` | Demo shopping page |
| `GET` | `/events/stream` | SSE stream (`text/event-stream`) |

### Dev / Seed

| Method | Path | Notes |
|---|---|---|
| `POST` | `/dev/seed?count=N&type=T` | Produce N synthetic events to Kafka (type: random\|click\|view\|purchase\|impression) |
| `POST` | `/dev/seed/items` | Write all 50 catalogue items to Redis with initial popularity scores |

---

## Project Structure

```
src/
├── main/
│   ├── kotlin/com/recengine/
│   │   ├── Application.kt                  # Ktor bootstrap, lifecycle wiring
│   │   ├── config/
│   │   │   └── AppConfig.kt                # Typesafe Config data classes
│   │   ├── dashboard/
│   │   │   ├── DashboardRoutes.kt          # GET /, GET /shop, SSE /events/stream
│   │   │   └── EventBroadcaster.kt         # SharedFlow(replay=50) Kafka → SSE bridge
│   │   ├── kafka/
│   │   │   ├── KafkaConsumerService.kt     # Flow<RecEngineEvent> from topic
│   │   │   ├── KafkaProducerService.kt     # Async event producer
│   │   │   └── TopicAdmin.kt              # Topic creation on startup
│   │   ├── metrics/
│   │   │   └── MetricsRegistry.kt          # Micrometer counters + latency histogram
│   │   ├── ml/
│   │   │   ├── OnlineFM.kt                 # AdaGrad SGD Factorisation Machine
│   │   │   ├── FeatureVectorBuilder.kt     # Hashing-trick feature construction
│   │   │   └── ScoringEngine.kt            # Composite score assembly + ranking
│   │   ├── model/
│   │   │   ├── Events.kt                   # Sealed class hierarchy (Click/View/Purchase/Impression)
│   │   │   ├── FeatureVector.kt            # ItemFeatures, UserProfile, FeatureVector
│   │   │   └── Recommendation.kt           # Request/response models, PipelineStats
│   │   ├── pipeline/
│   │   │   ├── EventProcessor.kt           # Supervised CoroutineScope stream consumer
│   │   │   └── SessionDecayJob.kt          # Periodic session vector decay
│   │   ├── redis/
│   │   │   ├── RedisClientFactory.kt       # Lettuce connection setup
│   │   │   ├── SessionStore.kt             # Session vectors + exponential decay
│   │   │   └── FeatureStore.kt             # Item features + popularity sorted sets
│   │   └── routing/
│   │       ├── AbAssigner.kt               # SHA-256 deterministic A/B assignment
│   │       ├── DevRoutes.kt                # Seed endpoints
│   │       ├── FeedbackRoutes.kt           # Event ingest endpoints
│   │       ├── RecommendationRoutes.kt     # Rec + trending endpoints
│   │       └── StatsRoutes.kt              # Pipeline stats endpoint
│   └── resources/
│       ├── application.conf                # HOCON config (Kafka, Redis, FM hyperparams)
│       ├── logback.xml
│       └── static/
│           ├── dashboard.html              # 4-tab monitoring dashboard
│           └── shop.html                   # Demo e-commerce storefront
└── test/
    └── kotlin/com/recengine/
        ├── kafka/KafkaRoundTripTest.kt     # TestContainers Kafka round-trip
        ├── ml/OnlineFMTest.kt              # FM convergence + thread-safety
        ├── pipeline/EventProcessorTest.kt  # Flow consumer unit tests
        ├── redis/SessionStoreTest.kt       # Decay + vector update tests
        └── routing/
            ├── AbAssignerTest.kt           # Determinism + distribution tests
            └── RecommendationRoutesTest.kt # Ktor testApplication route tests
```

---

## Configuration

`src/main/resources/application.conf` (HOCON):

```hocon
kafka {
  bootstrapServers = "localhost:9092"
  topicEvents      = "user-events"
  topicFeedback    = "feedback-events"
  groupId          = "recengine-processor"
}

redis {
  uri              = "redis://localhost:6379"
  sessionTtlSecs   = 86400
}

model {
  fm {
    numFeatures    = 4096
    numFactors     = 16
    learningRate   = 0.01
    regularization = 0.001
  }
  scoring {
    session        = 0.40
    content        = 0.35
    popularity     = 0.15
    recency        = 0.10
  }
}

ab {
  variants       = ["control", "fm_v1", "bandit_ucb"]
  buckets        = 1000
  ttlSeconds     = 604800
}
```

All values can be overridden via environment variables using Typesafe Config's substitution syntax (`${?KAFKA_BOOTSTRAP_SERVERS}`).

---

## Running Tests

```bash
# All tests (requires Docker for TestContainers)
./gradlew test

# Specific test class
./gradlew test --tests "com.recengine.ml.OnlineFMTest"

# Compile only (no Docker needed)
./gradlew compileKotlin compileTestKotlin
```

---

## How Recommendations Work

1. **On each event**, `EventProcessor` updates three Redis structures:
   - `session:{userId}:vector` — increments the weight for the item's category
   - `popularity:hourly` / `popularity:daily` — increments sorted set score for the item
   - Calls `OnlineFM.learn(featureVector, label)` where label = 1.0 for purchases/views, 0.5 for clicks

2. **On each recommendation request**, `ScoringEngine.scoreItems()`:
   - Reads the user's decayed session vector from `SessionStore`
   - Fetches up to 200 candidate items from the popularity sorted set
   - For each candidate, loads `ItemFeatures` from Redis and builds a hash-trick feature vector
   - Scores all four components and computes the weighted composite
   - Returns top-N ranked results

3. **Session decay** runs every 15 minutes: multiplies every value in every active session vector by `exp(-ln2 / (4h) × elapsed)`, so a 4-hour-old click has half the influence of a fresh one.
