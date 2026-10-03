# Home API

Frontend-facing Breathing House API (BFF) for `home-dashboard`. It is the
read/query boundary over historical environment and occupancy data. Alert
persistence and alert APIs are planned but not implemented yet.

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

Local/dev defaults use the shared `bh` user. Production should inject a
read-only database user through a Kubernetes Secret.

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

## Readiness

- `/live` is always `200` while the process is running
- `/ready` is `200` only when `environment.environment_reading` and
  `occupancy.occupancy_event` are queryable; otherwise `503`

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
