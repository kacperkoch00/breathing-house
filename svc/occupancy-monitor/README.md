# Occupancy Monitor

Go HTTP service that consumes Kafka topic `event-data` (consumer group
`occupancy-monitor`). Valid `PRESENCE` and `OPENING` events are persisted to
PostgreSQL table `occupancy.occupancy_event`. Offsets are committed only after a
successful insert (or idempotent conflict ignore). `/live` is always up when the
process is running. `/ready` requires Kafka and database connectivity.

## Local development

```bash
go test ./...
go run ./cmd/server
curl http://localhost:8081/live
curl http://localhost:8081/ready
```

Defaults expect Kafka at `localhost:9092` and Postgres at
`postgres://bh:bh@localhost:5432/breathing_house?sslmode=disable` (see
`deploy/k8s/postgres.yaml` / `make k8s-postgres`).

The service listens on port `8081` by default.

OpenAPI source is in `openapi.yaml`. Regenerate the typed server with
`make generate` from this directory (or from the repo root:
`make generate` / `make generate-service SERVICE=occupancy-monitor`).

## Metrics

Prometheus metrics are exposed at `GET /metrics`. Custom `/live` and `/ready`
are unchanged. Grafana dashboards and ServiceMonitor CRDs are out of scope;
scrape `/metrics` only.

| Counter | Labels | When |
| :------ | :----- | :--- |
| `kafka_messages_received_total` | `topic` | Once per Kafka record in `processFetches` |
| `kafka_fetch_errors_total` | `topic` | Once per Kafka fetch error in `EachError` |

```bash
curl http://localhost:8081/metrics
```

## Configuration

| Variable | Default | Notes |
| :------- | :------ | :---- |
| `HTTP_PORT` | `8081` | HTTP listen port |
| `SHUTDOWN_TIMEOUT` | `10s` | Graceful shutdown timeout |
| `LOG_LEVEL` | `info` | `debug` enables development logging |
| `KAFKA_BROKERS` | `localhost:9092` | Comma-separated bootstrap brokers |
| `KAFKA_CONSUMER_TOPIC` | `event-data` | Topic to consume |
| `KAFKA_CONSUMER_GROUP_ID` | `occupancy-monitor` | Consumer group |
| `KAFKA_RETRY_DELAY` | `5s` | Delay between Kafka readiness/poll retries |
| `DATABASE_URL` | `postgres://bh:bh@localhost:5432/breathing_house?sslmode=disable` | Postgres DSN (not logged) |
| `DATABASE_TIMEOUT` | `2s` | Timeout for ping and insert |
| `DATABASE_RETRY_DELAY` | `5s` | Delay between database readiness retries |

The Helm chart under `deploy/helm/occupancy-monitor` sets these for cluster
deployments (`KAFKA_BROKERS=kafka:9092`,
`DATABASE_URL=postgres://bh:bh@postgres:5432/breathing_house?sslmode=disable`).

## Container image

From the repository root (images are built with **podman**):

```bash
make image SERVICE=occupancy-monitor IMAGE=localhost/occupancy-monitor:dev
```

To publish to a registry, retag and push:

```bash
make image SERVICE=occupancy-monitor IMAGE=ghcr.io/<owner>/occupancy-monitor:0.1.0
docker push ghcr.io/<owner>/occupancy-monitor:0.1.0
```

## Kubernetes

Prefer Ingress (see the [root README](../../README.md#access-services-through-ingress)):

```bash
curl -H 'Host: occupancy-monitor.local' "http://$(minikube ip)/live"
```

From the repository root, install with a local image:

```bash
make build SERVICE=occupancy-monitor
make k8s-load SERVICE=occupancy-monitor IMAGE=localhost/occupancy-monitor:dev
make k8s-deploy SERVICE=occupancy-monitor
```

Or install the chart with an image from your container registry:

```bash
helm upgrade --install occupancy-monitor deploy/helm/occupancy-monitor \
  --set image.repository=ghcr.io/<owner>/occupancy-monitor \
  --set image.tag=0.1.0 \
  --set image.pullPolicy=IfNotPresent

kubectl rollout status deployment/occupancy-monitor
```

The chart configures port `8081` and uses `/live` and `/ready` for Kubernetes
probes. Fallback without Ingress:

```bash
kubectl port-forward service/occupancy-monitor 8081:8081
curl http://localhost:8081/live
```
