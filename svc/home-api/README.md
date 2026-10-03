# Home API

Frontend-facing Breathing House API (BFF) for `home-dashboard`. It is the
read/query boundary over historical environment and occupancy data. It also
evaluates configurable alert rules and persists alert lifecycle state.

## Architectural responsibility

`home-api` is the single backend API consumed by `home-dashboard`. The monitors
retain write-side persistence:

- `environment-monitor`: consume Kafka and persist environment readings
- `occupancy-monitor`: consume Kafka and persist occupancy events

## Run locally

Requires PostgreSQL with the shared init schema
(`deploy/k8s/postgres-init.sql`), for example via `make k8s-postgres` or a local
Postgres instance.

```bash
mvn -B test
mvn spring-boot:run
curl http://localhost:8082/live
curl http://localhost:8082/ready
```

Default JDBC settings:

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

Local/dev defaults use the shared `bh` user. Production should inject a user
that can read the monitor schemas and write only the `home_api` alert schema
through a Kubernetes Secret.

The service listens on port `8082` by default.

## History API

```bash
# Distinct room IDs from both history tables
curl http://localhost:8082/api/v1/rooms

# Environment history (newest first)
curl 'http://localhost:8082/api/v1/rooms/living-room/environment-readings?sensorType=AIR&limit=100&offset=0'

# Occupancy history (newest first)
curl 'http://localhost:8082/api/v1/rooms/living-room/occupancy-events?eventType=PRESENCE&limit=100&offset=0'
```

Optional filters:

- environment: `sensorType` (`ROOM`|`AIR`), `from`, `to` (RFC3339, inclusive)
- occupancy: `eventType` (`PRESENCE`|`OPENING`), `from`, `to` (RFC3339, inclusive)
- both: `limit` (1–500, default 100), `offset` (>= 0, default 0)

Pagination uses `limit + 1` internally and returns `hasMore`. Responses never
include Kafka topic/partition/offset fields. CORS is enabled for `/api/**`
against the configured origin allowlist.

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
  devices provide data for a leaf
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
contain explicit room IDs or `"*"`. Message placeholders are limited to
`{{roomId}}`, `{{deviceId}}`, `{{value}}`, `{{values}}`, `{{threshold}}`, and
`{{duration}}`; configuration cannot execute SQL or code. Composite messages
typically use `{{roomId}}`, `{{duration}}`, and `{{values}}`.

Pending condition state and active/resolved alert history are stored in
`home_api.alert_state` and `home_api.alert`. Flyway creates and upgrades these
tables at startup. Active alerts are deduplicated per rule, room, and device
(composites use a null device). Cleared conditions resolve rather than delete
alerts.

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
- `/ready` is `200` only when `environment.environment_reading` and
  `occupancy.occupancy_event`, `home_api.alert`, and `home_api.alert_state` are
  queryable; otherwise `503`

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
