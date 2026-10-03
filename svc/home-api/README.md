# Home API

Frontend-facing Breathing House API (BFF) for `home-dashboard`. It owns the
room and sensor domain model (explicit rooms, discovered sensors, and the
sensor-to-room assignment) and is the read/query boundary over historical
environment and occupancy data. It also evaluates configurable alert rules,
persists alert lifecycle state, and exposes sensor gateway online/offline
status from Kafka STATUS heartbeats.

## Architectural responsibility

`home-api` is the single backend API consumed by `home-dashboard`. The monitors
retain write-side persistence for environment and occupancy history:

- `environment-monitor`: consume Kafka and persist environment readings
- `occupancy-monitor`: consume Kafka and persist occupancy events

`home-api` additionally consumes Kafka topic `status-data` (STATUS heartbeats
produced by `sensors-data-collector`) and stores the complete heartbeat history
in `home_api.gateway_heartbeat`.

## Run locally

Requires PostgreSQL with the shared init schema
(`deploy/k8s/postgres-init.sql`), for example via `make k8s-postgres` or a local
Postgres instance. Kafka is required for STATUS heartbeat consumption (defaults
to `localhost:9092`, topic `status-data`).

```bash
mvn -B test
mvn spring-boot:run
curl http://localhost:8082/live
curl http://localhost:8082/ready
curl http://localhost:8082/api/v1/sensor-gateway/status
```

Default settings:

| Variable | Default |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/breathing_house` |
| `DATABASE_USERNAME` | `bh` |
| `DATABASE_PASSWORD` | `bh` |
| `DATABASE_CONNECTION_TIMEOUT_MS` | `2000` |
| `DATABASE_QUERY_TIMEOUT_SECONDS` | `2` |
| `CORS_ALLOWED_ORIGINS` | `http://home-dashboard.local,http://localhost:5173` |
| `ALERT_CONFIG_PATH` | packaged `alerts/default-alerts.json` |
| `ALERT_SCHEDULING_ENABLED` | `true` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `KAFKA_STATUS_TOPIC` | `status-data` |
| `KAFKA_STATUS_CONSUMER_GROUP_ID` | `home-api-gateway-status` |
| `SENSOR_GATEWAY_ID` | `gateway` |
| `SENSOR_GATEWAY_HEARTBEAT_TIMEOUT` | `30s` |
| `KAFKA_RETRY_DELAY` | `5s` |

Local/dev defaults use the shared `bh` user. Production should inject a user
that can read the monitor schemas and read/write the `home_api` room, sensor,
alert, and gateway heartbeat tables through a Kubernetes Secret.

The service listens on port `8082` by default.

## Tests

`mvn -B test` runs unit and H2-backed slice tests. The Flyway V4 data migration
is verified against real PostgreSQL by `SensorRoomDomainMigrationTest`, which
is skipped unless `TEST_DATABASE_URL` is set (CI sets it for the bundled
Postgres service). The user needs the `CREATE DATABASE` privilege; each test
migrates a throwaway database and drops it afterwards.

```bash
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/postgres \
TEST_DATABASE_USERNAME=bh TEST_DATABASE_PASSWORD=bh \
mvn -B test -Dtest=SensorRoomDomainMigrationTest
```

## Sensor gateway status

`sensors-data-collector` publishes STATUS envelopes to Kafka topic `status-data`:

```json
{
  "schemaVersion": 1,
  "roomId": "gateway",
  "deviceId": null,
  "type": "STATUS",
  "observedAt": "2026-10-03T10:00:00Z",
  "receivedAt": "2026-10-03T10:00:00Z",
  "values": {
    "status": "ONLINE",
    "uptime": 3600,
    "connected": true
  }
}
```

`values.status` must be a nonblank string (`ONLINE`, `OK`, `READY`, or any other
tool-specific value). Home API treats a fresh valid STATUS message as the
heartbeat; the reported status string is diagnostic only and does not control
online/offline.

Timeout semantics:

- gateway is online when the newest row for `SENSOR_GATEWAY_ID` has
  `received_at >= now - SENSOR_GATEWAY_HEARTBEAT_TIMEOUT`
- freshness uses `receivedAt` from the collector, not gateway `observedAt` and
  not DB `ingested_at`
- missing or expired heartbeats return `{"online":false}` with HTTP 200

```bash
curl http://localhost:8082/api/v1/sensor-gateway/status
# {"online":true}
```

Every valid heartbeat is retained. Duplicate Kafka topic/partition/offset
deliveries are idempotent. Invalid STATUS contracts are logged and skipped;
database failures retry the same record after `KAFKA_RETRY_DELAY` without
advancing the offset.

## Domain model: sensors and rooms

| Concept | Identifier | Presentation | Notes |
|---|---|---|---|
| Room | `roomId` (server-generated UUID, immutable) | `name`, `description` | Created explicitly through the API. Never deleted. |
| Sensor | `sensorId` (reported by the device, immutable) | `displayName` (defaults to `sensorId`) | Discovered automatically when it sends data. Cannot be created through the API. |

Rules:

- `roomId` and `sensorId` are technical identifiers used by APIs, alert rules,
  persisted alerts, and Kafka. `name` and `displayName` are presentation only,
  can be changed freely, and do not have to be unique.
- A sensor is in **at most one room** at a time. Assigning it to another room
  moves it atomically. A sensor can also be **unassigned** (`roomId: null`);
  newly discovered sensors start unassigned.
- Rooms never disappear. There is no room deletion endpoint.
- **Historical snapshots:** every environment reading and occupancy event stores
  the `room_id` the sensor was assigned to when the record was ingested. Moving
  a sensor never rewrites history: old records stay under the old room, new
  records go to the new room, and records ingested while the sensor is
  unassigned have no room and are not returned by any room endpoint.

### Rooms

```bash
# Create (201). roomId is generated by the server.
curl -X POST http://localhost:8082/api/v1/rooms \
  -H 'Content-Type: application/json' \
  -d '{"name":"Living Room","description":"Ground floor"}'
# {"roomId":"0b0d8f06-7d51-4a54-9c6f-3a0f8c5e0d11","name":"Living Room","description":"Ground floor","sensorIds":[]}

# List rooms from home_api.room (ordered by name, roomId) with current sensorIds
curl http://localhost:8082/api/v1/rooms
# {"rooms":[{"roomId":"...","name":"Living Room","description":"Ground floor","sensorIds":["air-1"]}]}

curl http://localhost:8082/api/v1/rooms/0b0d8f06-7d51-4a54-9c6f-3a0f8c5e0d11

# Partial update: name and/or description; empty patches are rejected (400)
curl -X PATCH http://localhost:8082/api/v1/rooms/0b0d8f06-7d51-4a54-9c6f-3a0f8c5e0d11 \
  -H 'Content-Type: application/json' \
  -d '{"name":"Salon","description":null}'
```

Validation: `name` is trimmed, must be a nonblank string and at most 100
characters. `description` is trimmed, optional, nullable, at most 500
characters; a blank description is stored as `null`. In a PATCH, an omitted
field is unchanged and `"description": null` clears it. Unknown room IDs return
`404`. The previous `PATCH {"displayName": ...}` body is gone; it is now an
empty patch and returns `400`.

### Sensors

```bash
curl http://localhost:8082/api/v1/sensors
# {"sensors":[{"sensorId":"air-1","displayName":"air-1","types":["AIR"],"roomId":null}]}

curl http://localhost:8082/api/v1/sensors/air-1

curl -X PATCH http://localhost:8082/api/v1/sensors/air-1 \
  -H 'Content-Type: application/json' \
  -d '{"displayName":"Kitchen air"}'
```

- `types` lists the distinct kinds of data seen for the sensor in history
  (`ROOM`, `AIR` from environment readings; `OPENING`, `PRESENCE` from occupancy
  events), sorted alphabetically. A sensor that has not produced history yet
  has an empty list.
- `displayName` is trimmed, nonblank, at most 100 characters; duplicates are
  allowed. Only discovered sensors can be renamed; unknown IDs return `404`.

### Assigning sensors to rooms

```bash
# Assign (or move) a sensor: 200 with the updated sensor
curl -X PUT http://localhost:8082/api/v1/rooms/<roomId>/sensors/air-1

# Unassign: 200 with the updated sensor ({"roomId":null})
curl -X DELETE http://localhost:8082/api/v1/rooms/<roomId>/sensors/air-1
```

- `PUT` is idempotent and atomic: the sensor is locked, moved out of its previous
  room, and alert state is reconciled in one transaction.
- `DELETE` returns `404` if the room or sensor does not exist and `409
  conflict` if the sensor is not currently in that room (including when it is
  unassigned).
- Errors use the shared `ApiError` shape: `{"error":"not_found" | "conflict" |
  "bad_request" | "database_error","message":"..."}`.

## History API

```bash
# Environment history (newest first); path uses the technical roomId
curl 'http://localhost:8082/api/v1/rooms/<roomId>/environment-readings?sensorType=AIR&limit=100&offset=0'

# Occupancy history (newest first)
curl 'http://localhost:8082/api/v1/rooms/<roomId>/occupancy-events?eventType=PRESENCE&limit=100&offset=0'
```

Optional filters:

- environment: `sensorType` (`ROOM`|`AIR`), `from`, `to` (RFC3339, inclusive)
- occupancy: `eventType` (`PRESENCE`|`OPENING`), `from`, `to` (RFC3339, inclusive)
- both: `limit` (1–500, default 100), `offset` (>= 0, default 0)

Records are selected by their **snapshotted** `room_id`, so a record stays in
the room the sensor was in when it was ingested. Response items expose
`sensorId` (breaking change: `deviceId` was removed). `sensorId` is read as
`COALESCE(sensor_id, device_id)` so rows written by not-yet-upgraded monitors
still show their sensor. It is `null` only for legacy rows that never had a
sensor identity. Unknown rooms return an empty page.

Pagination uses `limit + 1` internally and returns `hasMore`. Responses never
include Kafka topic/partition/offset fields or display names. CORS is enabled
for `/api/**` (`GET`, `POST`, `PATCH`, `PUT`, `DELETE`, `OPTIONS`) against the
configured origin allowlist.

## Schema versions and rolling deploy

Flyway `V4__sensor_room_domain.sql` introduces `home_api.room`,
`home_api.sensor`, and the additive columns `environment_reading.sensor_id` /
`occupancy_event.sensor_id`; `room_id` on both history tables becomes nullable
(null means "ingested while unassigned"). On upgrade it also:

1. creates a room for every `room_id` found in history, named from
   `home_api.room_metadata.display_name` (trimmed) or the old `room_id`;
2. backfills `sensor_id` from the trimmed, nonblank `device_id`;
3. creates one sensor per `sensor_id` (`displayName` = `sensorId`), assigned to
   the room of its newest record across both history tables;
4. keeps legacy rows without a `device_id` (they keep their `room_id`, have no
   sensor) and keeps the legacy `device_id` and `room_metadata` columns/tables.

Wire-format transition: the collector's schema v1 messages carry a room and a
`deviceId`; schema v2 messages carry only a `sensorId` and the monitors resolve
the sensor's current room at ingest time (snapshot). Monitors accept both while
the rollout is in progress. `home-api` already reads either shape
(`COALESCE(sensor_id, device_id)`) and treats legacy `device_id` values in
`alert.device_id` / `alert_state.device_id` as sensor IDs.

**Deploy order** (each step is backwards compatible with the previous one):

1. `home-api` (applies Flyway V4, serves the new API, reads both shapes)
2. `environment-monitor` and `occupancy-monitor` (register sensors, snapshot
   rooms, write `sensor_id`)
3. `sensors-data-collector` (starts publishing schema v2 messages)

`device_id` and `room_metadata` are removed by a later cleanup migration once
all writers are on v2.

## Alert evaluation

The service evaluates alert rules with a non-overlapping fixed delay. The
packaged defaults run every 10 seconds and include high CO2/humidity,
low humidity, high/low temperature, an opening left open, and stale AIR/ROOM
data.

Rules are loaded from `classpath:/alerts/default-alerts.json`. Set
`ALERT_CONFIG_PATH` to an external JSON file to override them. The file is
checked before every evaluation; a valid change is applied without restart.
When a changed file is invalid, the service logs the error and keeps using the
last valid configuration.

```json
{
  "evaluationInterval": "10s",
  "alerts": [{
    "id": "high-co2",
    "enabled": true,
    "type": "THRESHOLD",
    "source": "ENVIRONMENT",
    "sensorType": "AIR",
    "metric": "CO2",
    "operator": "GREATER_THAN",
    "threshold": 1500,
    "for": "5m",
    "maxDataAge": "2m",
    "severity": "WARNING",
    "rooms": ["*"],
    "message": "CO2 in {{roomId}} is {{value}} ppm"
  }]
}
```

Supported rule types:

- `THRESHOLD`: numeric ROOM/AIR metrics with an operator and hold duration
- `BOOLEAN_STATE`: `PRESENT` or `OPEN` equal to a configured boolean
- `STALE_DATA`: latest matching reading/event is older than `for`
- `COMPOSITE`: combine two or more leaf conditions for the same room

`COMPOSITE` rules use a `combinator` of `ALL` (every condition true) or `ANY`
(at least one true). Leaf conditions may be `THRESHOLD` or `BOOLEAN_STATE` and
may use different sensor types or sources. Nested composites and `STALE_DATA`
leaves are not supported. Parent leaf fields (`source`, `sensorType`,
`eventType`, `metric`, `operator`, `threshold`, `field`, `expected`,
`maxDataAge`) must not be set on the composite itself; each leaf carries its
own fields, including a required positive `maxDataAge`.

Composite evaluation is per room:

- the newest matching observation (by `observed_at`) is used when multiple
  sensors provide data for a leaf
- missing or older-than-`maxDataAge` leaf values evaluate as false
- the parent `rooms` filter and `for` hold duration apply to the combined result
- one alert/state is stored per `(rule_id, room_id)` with `device_id` null
- for `ALL`, the initial condition start is the latest `observed_at` among true
  leaves; for `ANY`, it is the earliest
- `trigger_value` is a JSON object keyed by condition id for true leaves, for
  example `{"co2":"1450","temperature":"29.5"}`

Example (not packaged in the defaults):

```json
{
  "id": "poor-air-and-hot",
  "enabled": true,
  "type": "COMPOSITE",
  "combinator": "ALL",
  "for": "5m",
  "severity": "WARNING",
  "rooms": ["*"],
  "message": "Poor conditions in {{roomId}}: {{values}}",
  "conditions": [
    {
      "id": "co2",
      "type": "THRESHOLD",
      "source": "ENVIRONMENT",
      "sensorType": "AIR",
      "metric": "CO2",
      "operator": "GREATER_THAN",
      "threshold": 1200,
      "maxDataAge": "2m"
    },
    {
      "id": "temperature",
      "type": "THRESHOLD",
      "source": "ENVIRONMENT",
      "sensorType": "ROOM",
      "metric": "TEMPERATURE",
      "operator": "GREATER_THAN",
      "threshold": 28,
      "maxDataAge": "2m"
    }
  ]
}
```

Durations accept `ms`, `s`, `m`, `h`, `d`, or ISO-8601 values. Room lists can
contain explicit technical `roomId` values (UUIDs for rooms created through the
API) or `"*"`. Message placeholders are limited to `{{roomId}}`, `{{sensorId}}`,
`{{value}}`, `{{values}}`, `{{threshold}}`, and `{{duration}}`; `{{deviceId}}`
is kept as a deprecated alias of `{{sensorId}}`. Configuration cannot execute
SQL or code. Composite messages typically use `{{roomId}}`, `{{duration}}`, and
`{{values}}`.

Pending condition state and active/resolved alert history are stored in
`home_api.alert_state` and `home_api.alert`. Flyway creates and upgrades these
tables at startup. Active alerts are deduplicated per rule, room, and sensor
(composites use a null sensor). The deprecated `device_id` columns now hold the
`sensorId`. Cleared conditions resolve rather than delete alerts.

Alerts follow the **current** sensor assignment:

- only a sensor's newest reading can drive alerts, and only while that
  reading's room snapshot equals the sensor's current room; readings from an
  unassigned sensor, or from a previous room, never create room alerts (this
  also stops stale-data rules from alerting an old room after a move)
- legacy history rows with no sensor identity at all keep alerting by their
  stored room
- when a sensor is moved or unassigned, its active alerts and pending state for
  the old room are resolved/deactivated in the same transaction and composite
  rules are re-evaluated for the affected rooms right away; existing alerts are
  never moved to the new room. A new alert appears in the new room only after
  the sensor reports from it

For local Helm installs, use the packaged defaults or provide a file:

```bash
helm upgrade --install home-api deploy/helm/home-api \
  --set-file alerts.config=./alerts.json
```

An existing ConfigMap with an `alerts.json` key can be used through
`alerts.existingConfigMap`. Alert REST endpoints and notification delivery are
not implemented yet.

## Readiness

- `/live` is always `200` while the process is running
- `/ready` is `200` only when `environment.environment_reading`,
  `occupancy.occupancy_event`, `home_api.alert`, `home_api.alert_state`,
  `home_api.gateway_heartbeat`, `home_api.room`, and `home_api.sensor` are
  queryable; otherwise `503`
- `/ready` does **not** require a recent heartbeat or an online gateway. Gateway
  downtime is exposed only by `GET /api/v1/sensor-gateway/status`, not by
  `/live` or `/ready`.

## Metrics

JVM and Micrometer metrics are exposed for Prometheus scraping at
`GET /actuator/prometheus`. Custom `/live` and `/ready` are unchanged. Grafana
dashboards and ServiceMonitor CRDs are out of scope; scrape the actuator path only.

```bash
curl http://localhost:8082/actuator/prometheus
```

## Container image

From the repository root (images are built with **podman**):

```bash
make image SERVICE=home-api IMAGE=localhost/home-api:dev
```

To publish to a registry, retag and push:

```bash
make image SERVICE=home-api IMAGE=ghcr.io/<owner>/home-api:0.1.0
docker push ghcr.io/<owner>/home-api:0.1.0
```

OpenAPI source is in `openapi.yaml`. Springdoc also exposes `/v3/api-docs` and
`/swagger-ui.html` while the service is running.

## Kubernetes

Prefer Ingress (see the [root README](../../README.md#access-services-through-ingress)):

```bash
curl -H 'Host: home-api.local' "http://$(minikube ip)/live"
curl -H 'Host: home-api.local' "http://$(minikube ip)/api/v1/rooms"
```

From the repository root, install with a local image:

```bash
make build SERVICE=home-api
make k8s-load SERVICE=home-api IMAGE=localhost/home-api:dev
make k8s-deploy SERVICE=home-api
```

Or install the chart with an image from your container registry:

```bash
helm upgrade --install home-api deploy/helm/home-api \
  --set image.repository=ghcr.io/<owner>/home-api \
  --set image.tag=0.1.0 \
  --set image.pullPolicy=IfNotPresent

kubectl rollout status deployment/home-api
```

The chart configures port `8082`, JDBC env defaults for in-cluster Postgres, and
uses `/live` and `/ready` for Kubernetes probes. Fallback without Ingress:

```bash
kubectl port-forward service/home-api 8082:8082
curl http://localhost:8082/live
```
