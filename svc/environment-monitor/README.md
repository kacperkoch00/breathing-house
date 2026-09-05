# Environment Monitor

HTTP service for room environment data. The service currently exposes liveness and
readiness endpoints while the monitoring functionality is being built.

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

## Container image

Run these commands from the repository root:

```bash
make image IMAGE=ghcr.io/<owner>/environment-monitor:0.1.0
docker push ghcr.io/<owner>/environment-monitor:0.1.0
```

## Kubernetes

From the repository root, install the chart with an image from your container
registry:

```bash
helm upgrade --install environment-monitor deploy/helm/environment-monitor \
  --set image.repository=ghcr.io/<owner>/environment-monitor \
  --set image.tag=0.1.0 \
  --set image.pullPolicy=IfNotPresent

kubectl rollout status deployment/environment-monitor-environment-monitor
```

The chart configures port `8080` and uses `/live` and `/ready` for Kubernetes
probes. Access it locally with:

```bash
kubectl port-forward service/environment-monitor-environment-monitor 8080:8080
curl http://localhost:8080/live
```

OpenAPI source is in `openapi.yaml`; regenerate the typed server with `make
generate` from the service directory.