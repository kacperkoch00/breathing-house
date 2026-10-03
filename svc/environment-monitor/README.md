# Environment Monitor

Go HTTP service that consumes Kafka topic `sensor-data` (consumer group
`environment-monitor`). Valid `ROOM` and `AIR` records are persisted to
PostgreSQL table `environment.environment_reading`. Offsets are committed only
after a successful persist (or idempotent conflict ignore). `/live` is always up
when the process is running. `/ready` requires Kafka and database connectivity.

## Message schemas

The consumer accepts both envelope versions on `sensor-data`:

| Field | `schemaVersion: 1` (legacy) | `schemaVersion: 2` |
| :---- | :-------------------------- | :----------------- |
| `roomId` | Required; stored as the reading's room snapshot | Rejected if present |
| `deviceId` | Optional; trimmed value becomes the `sensorId` | Rejected if present |
| `sensorId` | Not read | Required, trimmed, non-blank, max 200 characters |
| `type`, `observedAt`, `receivedAt`, `values` | Unchanged | Unchanged |

A v1 record without a (non-blank) `deviceId` has no sensor identity: it is stored
with the envelope `roomId` and a null `sensor_id`. Records that fail validation
are logged, skipped and their offset is committed.

## Sensor registration and room snapshot

Each valid record is persisted in one database transaction:

1. When the record has a `sensorId`, insert it into `home_api.sensor` with
   `display_name = sensorId` (truncated to the column's 100-character limit)
   using `ON CONFLICT DO NOTHING`. An existing sensor, including an edited
   display name or room assignment, is never modified.
2. Read the sensor's current `room_id` from `home_api.sensor`.
3. Insert into `environment.environment_reading` with `sensor_id`, the room
   snapshot, and `sensorId` copied into the deprecated `device_id` column.
   Duplicate `(kafka_topic, kafka_partition, kafka_offset)` rows are ignored.

The room snapshot is the sensor's room at persist time (`NULL` when the sensor is
unassigned), so moving a sensor only affects later readings. Schema-v1 records
keep the room from the envelope instead; the sensor is still registered when a
`deviceId` is present, but its assignment is not used for that record.

If any step fails the transaction is rolled back, the offset is not committed
and the same record is retried.

## Deployment order

The service requires `home_api.room` / `home_api.sensor` and the nullable
`environment_reading.sensor_id` / `room_id` columns:

1. Deploy `home-api` first (Flyway migration `V4__sensor_room_domain.sql`).
2. Deploy `environment-monitor` (accepts v1 and v2).
3. Deploy `sensors-data-collector` last, so it only starts emitting v2 after
   consumers understand it.

## Local development

```bash
go test ./...
go run ./cmd/server
```

`internal/storage` also has a test against a real Postgres, skipped unless
`TEST_DATABASE_URL` is set (it applies `deploy/k8s/postgres-init.sql`):

```bash
podman run --rm -d --name bh-pg -p 55432:5432 -e POSTGRES_PASSWORD=bh postgres:16
TEST_DATABASE_URL=postgres://postgres:bh@localhost:55432/postgres go test ./internal/storage
```

Defaults expect Kafka at `localhost:9092` and Postgres at
`postgres://bh:bh@localhost:5432/breathing_house?sslmode=disable` (see
`deploy/k8s/postgres.yaml` / `make k8s-postgres`).

The service listens on port `8080` by default. Check its health:

```bash
curl http://localhost:8080/live
curl http://localhost:8080/ready
```

OpenAPI source is in `openapi.yaml`. Regenerate the typed server with
`make generate` from this directory (or from the repo root:
`make generate` / `make generate-service SERVICE=environment-monitor`).

## Metrics

Prometheus metrics are exposed at `GET /metrics`. Custom `/live` and `/ready`
are unchanged. Grafana dashboards and ServiceMonitor CRDs are out of scope;
scrape `/metrics` only.

| Counter | Labels | When |
| :------ | :----- | :--- |
| `kafka_messages_received_total` | `topic` | Once per Kafka record in `processFetches` |
| `kafka_fetch_errors_total` | `topic` | Once per Kafka fetch error in `EachError` |

```bash
curl http://localhost:8080/metrics
```

## Configuration

| Variable | Default | Notes |
| :------- | :------ | :---- |
| `HTTP_PORT` | `8080` | HTTP listen port |
| `SHUTDOWN_TIMEOUT` | `10s` | Graceful shutdown timeout |
| `LOG_LEVEL` | `info` | `debug` enables development logging |
| `KAFKA_BROKERS` | `localhost:9092` | Comma-separated bootstrap brokers |
| `KAFKA_CONSUMER_TOPIC` | `sensor-data` | Topic to consume |
| `KAFKA_CONSUMER_GROUP_ID` | `environment-monitor` | Consumer group |
| `KAFKA_RETRY_DELAY` | `5s` | Delay between Kafka readiness/poll retries |
| `DATABASE_URL` | `postgres://bh:bh@localhost:5432/breathing_house?sslmode=disable` | Postgres DSN (not logged) |
| `DATABASE_TIMEOUT` | `2s` | Timeout for ping and persist transaction |
| `DATABASE_RETRY_DELAY` | `5s` | Delay between database readiness retries |

The Helm chart under `deploy/helm/environment-monitor` sets these for cluster
deployments (`KAFKA_BROKERS=kafka:9092`,
`DATABASE_URL=postgres://bh:bh@postgres:5432/breathing_house?sslmode=disable`).

## Container image

From the repository root (images are built with **podman**):

```bash
make image SERVICE=environment-monitor IMAGE=localhost/environment-monitor:dev
```

To publish to a registry, retag and push (for example with docker after tagging):

```bash
make image SERVICE=environment-monitor IMAGE=ghcr.io/<owner>/environment-monitor:0.1.0
docker push ghcr.io/<owner>/environment-monitor:0.1.0
```

## Kubernetes

Prefer Ingress (see the [root README](../../README.md#access-services-through-ingress)):

```bash
curl -H 'Host: environment-monitor.local' "http://$(minikube ip)/live"
```

From the repository root, install with a local image:

```bash
make build SERVICE=environment-monitor
make k8s-load SERVICE=environment-monitor IMAGE=localhost/environment-monitor:dev
make k8s-deploy SERVICE=environment-monitor
```

Or install the chart with an image from your container registry:

```bash
helm upgrade --install environment-monitor deploy/helm/environment-monitor \
  --set image.repository=ghcr.io/<owner>/environment-monitor \
  --set image.tag=0.1.0 \
  --set image.pullPolicy=IfNotPresent

kubectl rollout status deployment/environment-monitor
```

The chart configures port `8080` and uses `/live` and `/ready` for Kubernetes
probes. Fallback without Ingress:

```bash
kubectl port-forward service/environment-monitor 8080:8080
curl http://localhost:8080/live
```
