# Breathing House

Breathing House contains the services and deployment assets for the home environment monitoring system.

## Repository layout

```text
svc/                         Service source code
  environment-monitor/       Consumes Kafka and persists environment readings
  occupancy-monitor/         Consumes Kafka and persists occupancy events
  home-api/                  Frontend-facing API/BFF for room history and alerts
  home-dashboard/            Static Vite/React start page
  sensors-data-collector/    MQTT → Kafka collector
  gateway/                   Raspberry Pi MQTT broker setup
deploy/helm/                 One Helm chart per deployable service (+ mqtt-broker)
deploy/k8s/                  Plain Kubernetes manifests (Kafka, Postgres)
deploy/observability/        Loki, Alloy, Prometheus, and Grafana Helm values
tests/robot/                 Robot Framework night-regression suite
scripts/                     Desktop Minikube setup using GHCR images
Makefile                     Repository-wide build and deployment commands
.github/workflows/           Independent CI workflow per service (+ shared workflows)
docs/kubernetes-wsl.md       WSL Kubernetes and Ingress setup
docs/architecture/           Cross-service architecture notes
```

Kafka for local Kubernetes is installed by `make k8s-kafka` from `deploy/k8s/kafka.yaml`, not a Helm chart. Postgres is installed by `make k8s-postgres` from `deploy/k8s/postgres.yaml` (also not a Helm chart). MQTT uses `deploy/helm/mqtt-broker` via `make k8s-mqtt`.

Local Postgres (Minikube only): ClusterIP service `postgres:5432`, database `breathing_house`, user/password `bh`/`bh` (dev defaults in the Secret). First boot creates schemas `environment`, `occupancy`, and `home_api` tables for rooms/sensors plus append-only history tables with nullable `room_id` and `sensor_id` (no legacy `device_id`).

## Domain model (sensors and rooms)

- Physical sensors emit an immutable `sensorId`. Users may pair or unpair them, rename sensors (`displayName`), create rooms, and assign each sensor to at most one room.
- Rooms are first-class entities (`roomId` is a server-generated UUID for new rooms). Room `name` need not be unique.
- Every reading/event stores the room assignment that existed at ingest time. Moving a sensor does not rewrite history.
- See [docs/architecture/message-flow.md](docs/architecture/message-flow.md) for MQTT/Kafka envelopes, schema versions, and identifiers.

### Deployment order

1. Deploy **home-api** (Flyway V4 adds `home_api.room` / `home_api.sensor`, history `sensor_id`, nullable `room_id`, and backfills data; V5 drops the legacy `device_id` columns and `home_api.room_metadata` and renames the alert `device_*` columns to `sensor_*`).
2. Deploy **environment-monitor** and **occupancy-monitor** (schema v2 only; they no longer write `device_id`).
3. Deploy **sensors-data-collector** (schema-v2 MQTT topics and Kafka envelopes; gateway STATUS stays schema v1).

## Services

Each service has its own README with local development, testing, image, and
deployment instructions.

- **environment-monitor** (Go): consumes Kafka `sensor-data` (schema v2 only), auto-registers sensors, and persists environment readings with a room snapshot. `/ready` requires Kafka and database connectivity.
- **occupancy-monitor** (Go): consumes Kafka `event-data` (schema v2 only), auto-registers sensors, and persists occupancy events with a room snapshot. `/ready` requires Kafka and database connectivity.
- **sensors-data-collector** (Spring Boot): consumes MQTT `home/sensors/{type}` topics, validates `sensorId`, publishes schema-v2 Kafka envelopes keyed by `sensorId`. Gateway STATUS remains schema v1.
- **home-api** (Spring Boot): BFF for explicit rooms/sensors/assignments, room-scoped history (`sensorId` on items), sensor-gateway online status, alert evaluation, and a read-only alert API (`GET /api/v1/alerts`, `GET /api/v1/alerts/{id}`). `/ready` requires history, alert, gateway heartbeat, `home_api.room`, and `home_api.sensor` tables to be queryable.
- **home-dashboard** (React/Vite): static UI with mock data; not wired to live backends yet.

Backend services keep their HTTP contract in an `openapi.yaml` file. Go services
additionally use `oapi-codegen` to generate typed server interfaces (`make
generate` from the service directory, or root `make generate` /
`make generate-service`). Spring Boot services expose interactive documentation
at `/swagger-ui.html` and the generated document at `/v3/api-docs`.

## Install the complete system on Kubernetes

### Local images and charts

Install `kubectl`, Minikube, and the container runtime first by following
[docs/kubernetes-wsl.md](docs/kubernetes-wsl.md). From the repository root,
start Kubernetes and enable the NGINX Ingress controller:

```bash
make k8s-start
```

Build all service images (podman) and Helm charts:

```bash
make build-all
```

Load each local image into Minikube and install each chart with Ingress enabled.
`make k8s-deploy` also starts MQTT, Kafka, and Postgres (`k8s-start` + `k8s-mqtt` + `k8s-kafka` + `k8s-postgres`):

```bash
for service in environment-monitor occupancy-monitor home-api sensors-data-collector home-dashboard; do
  make k8s-load SERVICE="$service" IMAGE="localhost/$service:dev"
  make k8s-deploy SERVICE="$service" K8S_RELEASE="$service"
done
```

Wait for all services:

```bash
for service in environment-monitor occupancy-monitor home-api sensors-data-collector home-dashboard; do
  deployment=$(kubectl get deployment -l "app.kubernetes.io/name=$service" -o jsonpath='{.items[0].metadata.name}')
  kubectl rollout status "deployment/$deployment" --timeout=180s
done
```

### Published OCI charts and GHCR images

Log in to GHCR, then create an image pull secret if the packages are private:

```bash
echo "$GHCR_TOKEN" | helm registry login ghcr.io \
  --username <github-username> \
  --password-stdin

kubectl create secret docker-registry ghcr-pull-secret \
  --docker-server=ghcr.io \
  --docker-username=<github-username> \
  --docker-password=<github-token> \
  --docker-email=<email>
```

Set the chart version published by GitHub Actions and install all five OCI
charts with their matching images:

```bash
CHART_VERSION=0.1.0-ci.<github-run-number>
IMAGE_TAG=sha-<commit>

for service in environment-monitor occupancy-monitor home-api sensors-data-collector home-dashboard; do
  helm upgrade --install "$service" \
    "oci://ghcr.io/<owner>/charts/$service" \
    --version "$CHART_VERSION" \
    --set image.repository="ghcr.io/<owner>/$service" \
    --set image.tag="$IMAGE_TAG" \
    --set image.pullPolicy=IfNotPresent \
    --set imagePullSecrets[0].name=ghcr-pull-secret \
    --set ingress.enabled=true \
    --set ingress.hosts[0].host="$service.local" \
    --set ingress.hosts[0].paths[0].path=/ \
    --set ingress.hosts[0].paths[0].pathType=Prefix
done
```

Omit `imagePullSecrets[0].name=ghcr-pull-secret` when the images are public.
The NGINX Ingress controller is enabled by `make k8s-start` for Minikube.

### Desktop setup script (GHCR latest)

One-shot desktop Minikube install that pulls the newest GHCR images and installs
the **local** charts under `deploy/helm/<service>` (not OCI chart pull, and not
`make build-all` / local `localhost/*:dev` images).

Prerequisites: `kubectl`, `minikube`, `helm`, and a working `podman` or `docker`.

From the repository root:

```bash
./scripts/setup-desktop.sh
```

Optional env knobs (defaults shown):

```bash
OWNER=kacperkoch00 IMAGE_TAG=latest PULL_POLICY=Always \
  DRIVER=          GH_USER="$OWNER" GHCR_TOKEN= \
  SKIP_HOSTS=0 WITH_OBSERVABILITY=0 \
  MQTT_MODE=external MQTT_BROKER_IP=host.minikube.internal MQTT_BROKER_PORT=1883 \
  MQTT_REQUIRE_REACHABLE=0 \
  MQTT_CONTAINER_NAME=breathing-house-mosquitto \
  MQTT_IMAGE=eclipse-mosquitto:2.0.18 \
  ./scripts/setup-desktop.sh
```

- `DRIVER` — Minikube driver; unset auto-detects podman then docker
- `GHCR_TOKEN` — if set, helm registry login + `ghcr-pull-secret`; if unset, assume public images
- `SKIP_HOSTS=1` — skip `/etc/hosts` update
- `WITH_OBSERVABILITY=1` — also run `make k8s-observability` (Loki, Alloy, Prometheus, Grafana + Breathing House dashboards), wait for Grafana/Prometheus rollouts, and print Grafana access notes (`admin`/`admin`)
- `MQTT_MODE` — `external` (default: host Mosquitto container as a Raspberry Pi stand-in) or `in-cluster` (helm-install `mqtt-broker`)
- `MQTT_BROKER_IP` / `MQTT_BROKER_PORT` — address pods use for the external broker (default `host.minikube.internal:1883`); not host `localhost`
- `MQTT_REQUIRE_REACHABLE=1` — after cluster start, fail if a short-lived probe pod cannot TCP-connect to the external broker (default: warn only)
- `MQTT_CONTAINER_NAME` / `MQTT_IMAGE` — host Mosquitto container started when `MQTT_MODE=external`

#### Host MQTT (default) and publishing events

By default, `./scripts/setup-desktop.sh` starts (or reuses) a Mosquitto container on the host named `breathing-house-mosquitto`, skips the in-cluster `mqtt-broker` chart, and points `sensors-data-collector` at `host.minikube.internal:1883`. That host broker stands in for a future Raspberry Pi.

Publish from the **host** with `localhost` (not `host.minikube.internal`):

```bash
./scripts/publish-sensor-event.sh air
./scripts/publish-sensor-event.sh room --sensor-id living-room-1
./scripts/publish-sensor-event.sh opening --count 3
```

Types: `room` | `air` | `opening` | `presence` | `status`.

To keep the previous in-cluster broker instead:

```bash
MQTT_MODE=in-cluster ./scripts/setup-desktop.sh
```

If pods cannot reach `host.minikube.internal`, override `MQTT_BROKER_IP` (for example a LAN IP or `host.docker.internal` on some Docker Desktop setups) and verify:

```bash
kubectl run -it --rm --restart=Never mqtt-debug --image=busybox:1.36 -- \
  nc -z -vw 5 "$MQTT_BROKER_IP" "${MQTT_BROKER_PORT:-1883}"
```

What it does:

- Starts Minikube and enables Ingress
- Ensures host Mosquitto when `MQTT_MODE=external` (default), or installs in-cluster MQTT (`make k8s-mqtt`) when `MQTT_MODE=in-cluster`; always installs Kafka (`make k8s-kafka`) and Postgres (`make k8s-postgres`)
- Deploys all five services from `ghcr.io/$OWNER/<service>:$IMAGE_TAG` with `*.local` Ingress hosts
- Optionally updates `/etc/hosts` with the Minikube IP and the five hostnames
- Curls backend `/live` and dashboard `/` through Ingress, then prints URLs

Stop with `make k8s-stop` or delete the cluster with `minikube delete`.

### Observability (Loki + Prometheus + Grafana)

`make k8s-observability` (alias `make k8s-grafana`) installs into namespace `observability`:

- Loki + Alloy (log shipping)
- Prometheus (scrapes backend metrics; no ServiceMonitor CRDs)
- Grafana with pre-provisioned Loki and Prometheus datasources
- Provisioned dashboards in folder **Breathing House** from `deploy/observability/dashboards/` (overview + one board per backend service)

Admin login for local/CI is `admin` / `admin` (see `deploy/observability/grafana-values.yaml`).

Prometheus scrape targets assume services in the `default` namespace:

| Job | Target |
| :-- | :----- |
| `environment-monitor` | `environment-monitor.default.svc:8080/metrics` |
| `occupancy-monitor` | `occupancy-monitor.default.svc:8081/metrics` |
| `home-api` | `home-api.default.svc:8082/actuator/prometheus` |
| `sensors-data-collector` | `sensors-data-collector.default.svc:8083/actuator/prometheus` |

Access Grafana:

```bash
make k8s-grafana
kubectl -n observability port-forward svc/grafana 3000:80
# open http://localhost:3000  (admin / admin)
# Dashboards → Breathing House
```

Dashboards are applied as ConfigMap `breathing-house-dashboards` and mounted by the Grafana chart; rerunning `make k8s-observability` refreshes them. Desktop setup with `WITH_OBSERVABILITY=1` installs the same stack and boards.

### Access services through Ingress

Get the Minikube address:

```bash
MINIKUBE_IP="$(minikube ip)"
echo "$MINIKUBE_IP"
```

Add these hostnames to `/etc/hosts` for browser and curl access:

```text
<minikube-ip> environment-monitor.local
<minikube-ip> occupancy-monitor.local
<minikube-ip> home-api.local
<minikube-ip> sensors-data-collector.local
<minikube-ip> home-dashboard.local
```

Backend health checks go through Ingress:

```bash
curl -H 'Host: environment-monitor.local' "http://$MINIKUBE_IP/live"
curl -H 'Host: occupancy-monitor.local' "http://$MINIKUBE_IP/live"
curl -H 'Host: home-api.local' "http://$MINIKUBE_IP/live"
curl -H 'Host: sensors-data-collector.local' "http://$MINIKUBE_IP/live"
```

Open the dashboard at `http://home-dashboard.local` after adding the hosts
entry. Prefer Ingress over `kubectl port-forward` for local access.

## Root commands

Run commands from the repository root. Local images are built with **podman**
(`make image` / `make images` → `localhost/<service>:dev`):

```bash
make test
make generate
make image SERVICE=environment-monitor IMAGE=localhost/environment-monitor:dev
make images
make build SERVICE=environment-monitor
make build-all
make build-changes
make helm-lint
make helm-template
make helm-package
make k8s-start
make k8s-stop
make k8s-mqtt
make k8s-kafka
make k8s-postgres
make k8s-load SERVICE=environment-monitor
make k8s-deploy SERVICE=environment-monitor
make k8s-observability
make k8s-grafana
```

`make build SERVICE=<service>` builds one service, including its tests, OpenAPI generation where applicable, Helm lint, Helm packaging, and container image. `make build-all` runs the complete repository build and packages every chart. `make build-changes` builds and packages only services affected by the current Git changes; use `DIFF_BASE=<git-ref>` to choose the comparison base.

`make k8s-deploy` depends on `k8s-start`, `k8s-mqtt`, `k8s-kafka`, and `k8s-postgres`.

`make k8s-observability` / `make k8s-grafana` installs Loki, Alloy, Prometheus, and Grafana (Prometheus + Loki datasources plus Breathing House dashboards). `WITH_OBSERVABILITY=1` on the desktop setup script gets the same stack.

`make helm-template` and `make helm-package` operate on the selected service.
Set `SERVICE` to choose the chart, for example:

```bash
make helm-template SERVICE=occupancy-monitor
make helm-package SERVICE=occupancy-monitor
```

Each service has an independent GitHub Actions workflow. A change under a
service or its Helm chart starts only that service's CI workflow. Every workflow
runs its build, unit tests, integration smoke test, Helm lint, and container
image build. Pushes to `main` also publish the image to GitHub Container
Registry; pull requests build the image without publishing it.

After those checks, each workflow starts a temporary Kind Kubernetes cluster,
installs the NGINX Ingress controller, installs the service Helm chart with
the locally built image, waits for rollout, and verifies the service through
Ingress. On `main` pushes it instead installs the just-published OCI chart and
GHCR image. Backend services are checked through `/live`; the dashboard is
checked through `/`. This test uses Ingress directly and does not use
`kubectl port-forward`.

Images are published as:

```text
ghcr.io/<owner>/environment-monitor:latest
ghcr.io/<owner>/occupancy-monitor:latest
ghcr.io/<owner>/home-api:latest
ghcr.io/<owner>/sensors-data-collector:latest
ghcr.io/<owner>/home-dashboard:latest
```

Each push also receives an immutable `sha-<commit>` tag. After the first
publish, configure package visibility in GitHub. If a package remains private,
create an image pull secret and pass it through the service chart's
`imagePullSecrets` value.

The scheduled cleanup workflow removes versions older than seven days and keeps
up to eight retained versions for every service image and Helm chart: the seven
newest eligible immutable versions plus the protected `latest` tag. Newer
versions are retained until they become eligible for cleanup.

Each service chart is published alongside its image as an OCI artifact:

```text
oci://ghcr.io/<owner>/charts/environment-monitor
oci://ghcr.io/<owner>/charts/occupancy-monitor
oci://ghcr.io/<owner>/charts/home-api
oci://ghcr.io/<owner>/charts/sensors-data-collector
oci://ghcr.io/<owner>/charts/home-dashboard
```

The chart version is `0.1.0-ci.<github-run-number>` and its `appVersion` is the
image commit SHA. Install a published chart with the matching image tag:

```bash
helm upgrade --install home-api \
  oci://ghcr.io/<owner>/charts/home-api \
  --version 0.1.0-ci.<github-run-number> \
  --set image.repository=ghcr.io/<owner>/home-api \
  --set image.tag=sha-<commit>
```

The root `SERVICE` variable selects the service for service-specific commands. For example:

```bash
make SERVICE=environment-monitor image IMAGE=ghcr.io/<owner>/environment-monitor:0.1.0
```

Each service image is defined by its Dockerfile under `svc/<service>`.
Each service has its own Kubernetes configuration under `deploy/helm/<service>`.

The nightly regression workflow runs at midnight UTC and can also be started
manually from GitHub Actions. It installs all five services into Kind, runs the
Robot Framework suite under `tests/robot`, and uploads the Robot report and
Kubernetes diagnostics as the `night-regression-results-<run-number>` artifact.

The scheduled logging-integration workflow installs a Kind logging stack
(MQTT/Kafka plus observability components) and verifies log shipping; see
`.github/workflows/logging-integration.yaml`.

The scheduled grafana-integration workflow installs the same observability
stack plus Prometheus, deploys the four backend services, and asserts Grafana
health, the Prometheus datasource, and scrape `up` for all four metrics jobs;
see `.github/workflows/grafana-integration.yaml`.

The biweekly release workflow runs at midnight UTC every 14 days on Mondays and
can also be started manually from GitHub Actions. It creates a dated GitHub
Release from the current `main` commit when commits exist since the previous
release, and generates release notes from merged changes.

For a local Kubernetes cluster on WSL, see
[docs/kubernetes-wsl.md](docs/kubernetes-wsl.md). The short workflow is:

```bash
make build SERVICE=environment-monitor
make k8s-load SERVICE=environment-monitor
make k8s-mqtt
make k8s-kafka
make k8s-postgres
make k8s-observability
make k8s-deploy SERVICE=environment-monitor
```

Install the chart into Kubernetes:

```bash
helm upgrade --install environment-monitor deploy/helm/environment-monitor
```

Install another service by changing the chart and release name, for example:

```bash
helm upgrade --install occupancy-monitor deploy/helm/occupancy-monitor
```
