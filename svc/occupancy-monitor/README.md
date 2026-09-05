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
make build SERVICE=occupancy-monitor
```

OpenAPI source is in `openapi.yaml`; generated server code is refreshed by the
service Makefile.