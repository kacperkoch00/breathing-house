# Alert Notifier

Minimal Spring Boot 3 service running on Java 21. It currently exposes health
endpoints while alert delivery is being built.

```bash
mvn -B test
mvn spring-boot:run
curl http://localhost:8082/live
curl http://localhost:8082/ready
```

The service listens on port `8082` by default. From the repository root, build
its image and Helm chart with:

```bash
make image SERVICE=alert-notifier IMAGE=ghcr.io/<owner>/alert-notifier:0.1.0
docker push ghcr.io/<owner>/alert-notifier:0.1.0
```

OpenAPI source is in `openapi.yaml`. Springdoc also exposes `/v3/api-docs` and
`/swagger-ui.html` while the service is running.

## Kubernetes

From the repository root, install the chart with an image from your container
registry:

```bash
helm upgrade --install alert-notifier deploy/helm/alert-notifier \
	--set image.repository=ghcr.io/<owner>/alert-notifier \
	--set image.tag=0.1.0 \
	--set image.pullPolicy=IfNotPresent

kubectl rollout status deployment/alert-notifier-alert-notifier
```

The chart configures port `8082` and uses `/live` and `/ready` for Kubernetes
probes. Access it locally with:

```bash
kubectl port-forward service/alert-notifier-alert-notifier 8082:8082
curl http://localhost:8082/live
```