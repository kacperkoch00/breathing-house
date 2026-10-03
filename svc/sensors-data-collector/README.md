# Sensors Data Collector

Minimal Spring Boot 3 service running on Java 21. It consumes sensor telemetry
from the MQTT broker, transforms it into a common sensor data model, and
publishes the transformed data to Kafka.

The service listens on port `8083` by default and exposes health endpoints.
`/live` is always up when the process is running. `/ready` requires both an
MQTT `CONNECTED` state and a successful Kafka cluster probe (2s timeout).

```bash
mvn -B test
mvn spring-boot:run
curl http://localhost:8083/live
curl http://localhost:8083/ready
```

The service consumes the following MQTT topics:

* `home/sensors/room`
* `home/sensors/air`
* `home/sensors/opening`
* `home/sensors/presence`
* `home/gateway/status`

Sensor topics carry no room identity: the sensor type is the last topic segment
and the sensor is identified by the `sensorId` field in the payload. Messages on
other topics (including the old `home/<room>/<type>` form) are ignored as
`invalid_topic`.

Sensor data is transformed according to its sensor type and published to the
following Kafka topics:

| Sensor Type | Kafka Topic   |
| :---------- | :------------ |
| `ROOM`      | `sensor-data` |
| `AIR`       | `sensor-data` |
| `OPENING`   | `event-data`  |
| `PRESENCE`  | `event-data`  |
| `STATUS`    | `status-data` |

On MQTT reconnect, the collector resubscribes when the broker reports no
existing session. Publish handling is registered once so reconnects do not
duplicate message delivery. Subscription QoS defaults to `1` (`MQTT_QOS`).

## Building

From the repository root, build the service image with **podman**:

```bash
make image SERVICE=sensors-data-collector IMAGE=localhost/sensors-data-collector:dev
```

To publish to a registry, retag and push:

```bash
make image SERVICE=sensors-data-collector IMAGE=ghcr.io/<owner>/sensors-data-collector:0.1.0
docker push ghcr.io/<owner>/sensors-data-collector:0.1.0
```

OpenAPI source is in `openapi.yaml`. Springdoc also exposes `/v3/api-docs` and
`/swagger-ui.html` while the service is running.

## Environment Variables

The application can be configured at runtime using the following environment
variables. If an environment variable is omitted, the service automatically
falls back to its default local development value.

| Environment Variable      | Description                                       | Local Default Value                                                         | Java Property Mapping            |
| :------------------------ | :------------------------------------------------ | :-------------------------------------------------------------------------- | :------------------------------- |
| `HTTP_PORT`               | HTTP listen port                                  | `8083`                                                                      | `server.port`                    |
| `MQTT_BROKER_IP`          | IPv4 address or hostname of the MQTT broker       | `localhost`                                                                 | `mqtt.broker-ip`                 |
| `MQTT_BROKER_PORT_NUMBER` | Network port for the MQTT 5 broker                | `1883`                                                                      | `mqtt.broker-port`               |
| `MQTT_CLIENT_ID`          | Base identifier string for this microservice node | `sensors-data-collector`                                                    | `mqtt.client-id`                 |
| `MQTT_CONSUMER_TOPICS`    | Comma-separated list of target sensor topics      | `home/sensors/room,home/sensors/air,home/sensors/opening,home/sensors/presence,home/gateway/status` | `mqtt.consumer-topics`           |
| `MQTT_INITIAL_DELAY_MS`   | Starting delay for reconnect attempts             | `1000`                                                                      | `mqtt.initial-delay-ms`          |
| `MQTT_MAX_DELAY_MS`       | Maximum delay between reconnect attempts          | `60000`                                                                     | `mqtt.max-delay-ms`              |
| `MQTT_QOS`                | MQTT subscription QoS (0, 1, or 2)                | `1`                                                                         | `mqtt.qos`                       |
| `KAFKA_BOOTSTRAP_SERVERS` | Comma-separated list of Kafka bootstrap servers   | `localhost:9092`                                                            | `spring.kafka.bootstrap-servers` |
| `KAFKA_SENSOR_TOPIC`      | Kafka topic for room and air sensor data          | `sensor-data`                                                               | `kafka.producer.topics.sensor`   |
| `KAFKA_EVENT_TOPIC`       | Kafka topic for opening and presence events       | `event-data`                                                                | `kafka.producer.topics.event`    |
| `KAFKA_STATUS_TOPIC`      | Kafka topic for gateway status data               | `status-data`                                                               | `kafka.producer.topics.status`   |
| `KAFKA_DLQ_TOPIC`         | Kafka topic for invalid (poison) sensor payloads  | `sensor-data-dlq`                                                           | `kafka.producer.topics.dlq`      |

### Setting Environment Variables in Kubernetes

The Helm chart can be configured using the `env` values. For example:

```yaml
env:
  HTTP_PORT: "8083"
  MQTT_BROKER_IP: "mqtt-broker"
  MQTT_BROKER_PORT_NUMBER: "1883"
  KAFKA_BOOTSTRAP_SERVERS: "kafka:9092"
```

The Kafka topic names can also be overridden:

```yaml
env:
  KAFKA_BOOTSTRAP_SERVERS: "kafka:9092"
  KAFKA_SENSOR_TOPIC: "sensor-data"
  KAFKA_EVENT_TOPIC: "event-data"
  KAFKA_STATUS_TOPIC: "status-data"
  KAFKA_DLQ_TOPIC: "sensor-data-dlq"
```

## Kubernetes

Prefer Ingress (see the [root README](../../README.md#access-services-through-ingress)):

```bash
curl -H 'Host: sensors-data-collector.local' "http://$(minikube ip)/live"
```

From the repository root, install the chart with an image from your container
registry:

```bash
helm upgrade --install sensors-data-collector deploy/helm/sensors-data-collector \
  --set image.repository=ghcr.io/<owner>/sensors-data-collector \
  --set image.tag=0.1.0 \
  --set image.pullPolicy=IfNotPresent

kubectl rollout status deployment/sensors-data-collector
```

The chart configures port `8083` and uses `/live` and `/ready` for Kubernetes
probes. Ready probes depend on MQTT connectivity and Kafka reachability.

Fallback without Ingress:

```bash
kubectl port-forward service/sensors-data-collector 8083:8083
curl http://localhost:8083/live
```

### Local Kubernetes deployment

When using the repository's local Kubernetes setup, MQTT and Kafka are
available through the following service addresses:

```text
MQTT:  mqtt-broker:1883
Kafka: kafka:9092
```

The service can be deployed with:

```bash
make build SERVICE=sensors-data-collector
make k8s-load SERVICE=sensors-data-collector IMAGE=localhost/sensors-data-collector:dev
make k8s-deploy SERVICE=sensors-data-collector
```

Kafka topics can be verified with:

```bash
kubectl exec deployment/kafka -- \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --list
```

The expected topics are:

```text
event-data
sensor-data
sensor-data-dlq
status-data
```

## Payload Validation

Each sensor type requires specific fields before a message is published to Kafka.
Invalid payloads raise `InvalidSensorPayloadException`, are logged by the handler,
are **not** published to `sensor-data` / `event-data` / `status-data`, and are
written to the DLQ topic (`sensor-data-dlq` by default) as a `PoisonMessage`.

| Sensor Type | Required Fields | Rules |
| :---------- | :-------------- | :---- |
| `ROOM`      | `sensorId` (string), `temperature`, `light` (numbers) | temperature ∈ [-40, 80]; light ≥ 0 (lux). Then `lightLevel` is derived |
| `AIR`       | `sensorId` (string), `temperature`, `humidity`, `co2` (numbers) | temperature ∈ [-40, 80]; humidity ∈ [0, 100]; co2 ∈ [0, 10000] |
| `OPENING`   | `sensorId` (string), `state` (string) | `OPEN` / `CLOSED` (case-insensitive) → `open` boolean |
| `PRESENCE`  | `sensorId` (string), `presence` (string) | `DETECTED` / `CLEAR` (case-insensitive) → `present` boolean |
| `STATUS`    | `status` (string) | non-blank |

Every non-status payload must include `sensorId`: a textual, non-blank value of at
most 200 characters after trimming (the trimmed value is used). Missing,
`null`, blank, or non-string `sensorId` values are rejected to the DLQ.
`STATUS` payloads do not use `sensorId`. The optional `timestamp` field remains
optional. Empty `{}` payloads for `ROOM` / `AIR` / `STATUS` are rejected.

## Metrics

Micrometer counters for the collector pipeline are exposed for Prometheus scraping at
`GET /actuator/prometheus`. Custom `/live` and `/ready` are unchanged. Grafana
dashboards and ServiceMonitor CRDs are out of scope; scrape the actuator path only.

| Counter | Tags | When |
| :------ | :--- | :--- |
| `sensor.messages.received` | `type` (`ROOM`, `AIR`, …) | Before transform, after type/transformer resolved |
| `sensor.messages.published` | `type` | Kafka ack success for transformed sensor data |
| `sensor.messages.rejected` | `type` | Invalid payload (before DLQ publish) |
| `sensor.messages.ignored` | `reason` (`invalid_topic`, `unknown_type`, `no_transformer`) | Message dropped without transform |
| `sensor.publish.failed` | `kind` (`sensor`, `dlq`) | Kafka publish failure in `whenComplete` |

```bash
curl http://localhost:8083/actuator/prometheus
```

## Kafka Message Envelope

Transformed sensor and occupancy messages (`ROOM`, `AIR`, `OPENING`, `PRESENCE`)
use schema version `2` (`SensorData`):

| Field           | Type              | Description                                                                 |
| :-------------- | :---------------- | :-------------------------------------------------------------------------- |
| `schemaVersion` | `int`             | `2` (`SensorData.SCHEMA_VERSION`)                                           |
| `sensorId`      | `string`          | Trimmed, non-blank `sensorId` from the MQTT payload (max 200 characters)    |
| `type`          | `SensorType`      | `ROOM`, `AIR`, `OPENING`, or `PRESENCE`                                     |
| `observedAt`    | RFC3339 string    | From payload `timestamp` when parseable; otherwise set at transform time (`Instant` in Java) |
| `receivedAt`    | RFC3339 string    | Always set to transform time (`Instant.now()` in Java)                      |
| `values`        | `object`          | Sensor-specific fields, without `sensorId`, `timestamp`, or envelope fields |

Schema-v2 envelopes have no `roomId` or `deviceId`; `values` never contains
`sensorId`, `timestamp`, `deviceId`, `roomId`, or other envelope fields.

`STATUS` messages (on `status-data`) stay on schema version `1`
(`SensorData.STATUS_SCHEMA_VERSION`) with `roomId` (`gateway`, from the topic) and
optional `deviceId` from the payload, and no `sensorId`. Null fields are omitted
from the JSON. Kafka `Instant` fields (`observedAt`, `receivedAt`, and DLQ
`rejectedAt`) are serialized as RFC3339 strings (ISO-8601 with `Z`), not epoch
numbers.

Payload `timestamp` parsing: ISO-8601 strings via `Instant.parse`; numbers greater
than `1e12` as epoch millis, otherwise epoch seconds. Unparseable values fall back
to transform time without failing the message.

Kafka record keys: `sensorId` for `ROOM` / `AIR` / `OPENING` / `PRESENCE`, and
`roomId` (`gateway`) for `STATUS`. DLQ records are keyed by the topic `roomId`, so
they have no key for non-status sensors.

## End-to-End Flow

The service processes sensor data using the following flow:

```text
MQTT Broker
    |
    v
SensorDataHandler
    |
    v
SensorDataTransformer
    |
    v
SensorData
    |
    v
TransformedSensorDataProducer
    |
    v
Kafka
```

The Kafka destination depends on the sensor type:

```text
ROOM       ─┐
AIR        ─┴─> sensor-data

OPENING    ─┐
PRESENCE   ─┴─> event-data

STATUS     ────> status-data
```
