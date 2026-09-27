# Alert Notifier

Minimal Spring Boot 3 service running on Java 21. It currently exposes health
endpoints while alert delivery is being built.

```bash
mvn -B test
mvn spring-boot:run
curl http://localhost:8082/live
curl http://localhost:8082/ready
```

The service listens on port `8082` by default.

## Container image

From the repository root (images are built with **podman**):

```bash
make image SERVICE=alert-notifier IMAGE=localhost/alert-notifier:dev
```

To publish to a registry, retag and push:

```bash
make image SERVICE=alert-notifier IMAGE=ghcr.io/<owner>/alert-notifier:0.1.0
docker push ghcr.io/<owner>/alert-notifier:0.1.0
```

OpenAPI source is in `openapi.yaml`. Springdoc also exposes `/v3/api-docs` and
`/swagger-ui.html` while the service is running.

## Kubernetes

Prefer Ingress (see the [root README](../../README.md#access-services-through-ingress)):

```bash
curl -H 'Host: alert-notifier.local' "http://$(minikube ip)/live"
```

From the repository root, install with a local image:

```bash
make build SERVICE=alert-notifier
make k8s-load SERVICE=alert-notifier IMAGE=localhost/alert-notifier:dev
make k8s-deploy SERVICE=alert-notifier
```

Or install the chart with an image from your container registry:

```bash
helm upgrade --install alert-notifier deploy/helm/alert-notifier \
  --set image.repository=ghcr.io/<owner>/alert-notifier \
  --set image.tag=0.1.0 \
  --set image.pullPolicy=IfNotPresent

kubectl rollout status deployment/alert-notifier
```

The chart configures port `8082` and uses `/live` and `/ready` for Kubernetes
probes. Fallback without Ingress:

```bash
kubectl port-forward service/alert-notifier 8082:8082
curl http://localhost:8082/live
```
