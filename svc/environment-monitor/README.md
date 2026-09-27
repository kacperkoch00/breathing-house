# Environment Monitor

Go HTTP service that consumes Kafka topic `sensor-data` (consumer group
`environment-monitor`). It polls and logs records today; domain/business logic
is still thin. `/live` is always up when the process is running. `/ready`
requires a successful Kafka ping.

## Local development

```bash
go test ./...
go run ./cmd/server
```

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

The Helm chart under `deploy/helm/environment-monitor` sets these for cluster
deployments (`KAFKA_BROKERS=kafka:9092`).

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
