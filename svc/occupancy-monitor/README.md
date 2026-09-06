# Occupancy Monitor

Minimal Go HTTP service for occupancy monitoring. It currently exposes health
endpoints while occupancy logic is being built.

```bash
go test ./...
go run ./cmd/server
curl http://localhost:8081/live
curl http://localhost:8081/ready
```

The service listens on port `8081` by default. From the repository root, build
its image and Helm chart with:

```bash
make image SERVICE=occupancy-monitor IMAGE=ghcr.io/<owner>/occupancy-monitor:0.1.0
docker push ghcr.io/<owner>/occupancy-monitor:0.1.0
```

OpenAPI source is in `openapi.yaml`; generated server code is refreshed by the
service Makefile.

## Kubernetes

From the repository root, install the chart with an image from your container
registry:

```bash
helm upgrade --install occupancy-monitor deploy/helm/occupancy-monitor \
	--set image.repository=ghcr.io/<owner>/occupancy-monitor \
	--set image.tag=0.1.0 \
	--set image.pullPolicy=IfNotPresent

kubectl rollout status deployment/occupancy-monitor
```

The chart configures port `8081` and uses `/live` and `/ready` for Kubernetes
probes. Access it locally with:

```bash
kubectl port-forward service/occupancy-monitor 8081:8081
curl http://localhost:8081/live
```