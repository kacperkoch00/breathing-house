# Home API

Frontend-facing Breathing House API (BFF) for `home-dashboard`. This branch
establishes service identity and future responsibility; currently only health
and metrics endpoints are implemented. History and alert APIs are not
implemented yet.

## Architectural responsibility

`home-api` is the single backend API consumed by `home-dashboard`. It is the
read/query boundary over historical environment and occupancy data, and the
owner of alert persistence, alert lifecycle, and alert-facing endpoints.

Eventually it will expose latest room state, historical data, and alerts.

The monitors retain these responsibilities:

- `environment-monitor`: consume Kafka and persist environment readings
- `occupancy-monitor`: consume Kafka and persist occupancy events

```bash
mvn -B test
mvn spring-boot:run
curl http://localhost:8082/live
curl http://localhost:8082/ready
```

The service listens on port `8082` by default.

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

The chart configures port `8082` and uses `/live` and `/ready` for Kubernetes
probes. Fallback without Ingress:

```bash
kubectl port-forward service/home-api 8082:8082
curl http://localhost:8082/live
```
