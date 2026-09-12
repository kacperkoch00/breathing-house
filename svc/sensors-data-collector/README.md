# Sensors Data Collector

Minimal Spring Boot 3 service running on Java 21. It consumes sensor telemetry
from the MQTT broker, transforms it into a common sensor data model, and
publishes the transformed data to Kafka.

The service listens on port `8083` by default and exposes health endpoints.

```bash
mvn -B test
mvn spring-boot:run
curl http://localhost:8083/live
curl http://localhost:8083/ready
```

The service consumes the following MQTT topics:

* `home/+/room`
* `home/+/air`
* `home/+/opening`
* `home/+/presence`
* `home/gateway/status`

Sensor data is transformed according to its sensor type and published to the
following Kafka topics:

| Sensor Type | Kafka Topic   |
| :---------- | :------------ |
| `ROOM`      | `sensor-data` |
| `AIR`       | `sensor-data` |
| `OPENING`   | `event-data`  |
| `PRESENCE`  | `event-data`  |
| `STATUS`    | `status-data` |

## Building

From the repository root, build the service image and Helm chart with:

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

| Environment Variable      | Description                                       | Local Default Value                                                         | Java Property Mapping          |
| :------------------------ | :------------------------------------------------ | :-------------------------------------------------------------------------- | :----------------------------- |
| `MQTT_BROKER_IP`          | IPv4 address or hostname of the MQTT broker       | `localhost`                                                                 | `mqtt.broker.ip`               |
| `MQTT_BROKER_PORT_NUMBER` | Network port for the MQTT 5 broker                | `1883`                                                                      | `mqtt.broker.port`             |
| `MQTT_CLIENT_ID`          | Base identifier string for this microservice node | `sensors-data-collector`                                                    | `mqtt.client.id`               |
| `MQTT_CONSUMER_TOPICS`    | Comma-separated list of target sensor topics      | `home/+/room,home/+/air,home/+/opening,home/+/presence,home/gateway/status` | `mqtt.consumer.topics`         |
| `MQTT_INITIAL_DELAY_MS`   | Starting delay for reconnect attempts             | `1000`                                                                      | `mqtt.initial.delay.ms`        |
| `MQTT_MAX_DELAY_MS`       | Maximum delay between reconnect attempts          | `60000`                                                                     | `mqtt.max.delay.ms`            |
| `KAFKA_BOOTSTRAP_SERVERS` | Comma-separated list of Kafka bootstrap servers   | `localhost:9092`                                                            | `kafka.bootstrap-servers`      |
| `KAFKA_SENSOR_TOPIC`      | Kafka topic for room and air sensor data          | `sensor-data`                                                               | `kafka.producer.topics.sensor` |
| `KAFKA_EVENT_TOPIC`       | Kafka topic for opening and presence events       | `event-data`                                                                | `kafka.producer.topics.event`  |
| `KAFKA_STATUS_TOPIC`      | Kafka topic for gateway status data               | `status-data`                                                               | `kafka.producer.topics.status` |

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
```

## Kubernetes

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
probes.

Access it locally with:

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
make k8s-load SERVICE=sensors-data-collector
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
status-data
```

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
