# Sensors Data Collector

Minimal Spring Boot 3 service running on Java 21. It currently exposes health
endpoints while sensor ingestion is being built.

```bash
mvn -B test
mvn spring-boot:run
curl http://localhost:8083/live
curl http://localhost:8083/ready
```

The service listens on port `8083` by default. From the repository root, build
its image and Helm chart with:

```bash
make image SERVICE=sensors-data-collector IMAGE=ghcr.io/<owner>/sensors-data-collector:0.1.0
docker push ghcr.io/<owner>/sensors-data-collector:0.1.0
```

OpenAPI source is in `openapi.yaml`. Springdoc also exposes `/v3/api-docs` and
`/swagger-ui.html` while the service is running.

## Kubernetes

From the repository root, install the chart with an image from your container
registry:

```bash
helm upgrade --install sensors-data-collector deploy/helm/sensors-data-collector \
	--set image.repository=ghcr.io/<owner>/sensors-data-collector \
	--set image.tag=0.1.0 \
	--set image.pullPolicy=IfNotPresent

kubectl rollout status deployment/sensors-data-collector-sensors-data-collector
```

The chart configures port `8083` and uses `/live` and `/ready` for Kubernetes
probes. Access it locally with:

```bash
kubectl port-forward service/sensors-data-collector-sensors-data-collector 8083:8083
curl http://localhost:8083/live
```