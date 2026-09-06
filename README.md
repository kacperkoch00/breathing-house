# Breathing House

Breathing House contains the services and deployment assets for the home environment monitoring system.

## Repository layout

```text
svc/                         Service source code
  environment-monitor/       Environment monitoring service
  alert-notifier/            Alert notification service
  home-dashboard/            Home dashboard
  occupancy-monitor/         Occupancy monitoring service
  sensors-data-collector/    Sensor data collection service
deploy/helm/                 One Helm chart per deployable service
Makefile                     Repository-wide build and deployment commands
.github/workflows/           Independent CI workflow per service
docs/kubernetes-wsl.md       WSL Kubernetes and Ingress setup
```

## Services

Each service has its own README with local development, testing, image, and
deployment instructions. Backend services keep their HTTP contract in an
`openapi.yaml` file. Go services additionally use `oapi-codegen` to generate
typed server interfaces. Spring Boot services expose interactive documentation
at `/swagger-ui.html` and the generated document at `/v3/api-docs`.

## Install the complete system on Kubernetes

### Local images and charts

Install `kubectl`, Minikube, and the container runtime first by following
[docs/kubernetes-wsl.md](docs/kubernetes-wsl.md). From the repository root,
start Kubernetes and enable the NGINX Ingress controller:

```bash
make k8s-start
```

Build all service images and Helm charts:

```bash
make build-all
```

Load each local image into Minikube and install each chart with Ingress enabled:

```bash
for service in environment-monitor occupancy-monitor alert-notifier sensors-data-collector home-dashboard; do
  make k8s-load SERVICE="$service" IMAGE="$service:dev"
  make k8s-deploy SERVICE="$service" K8S_RELEASE="$service"
done
```

Wait for all services:

```bash
for service in environment-monitor occupancy-monitor alert-notifier sensors-data-collector home-dashboard; do
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

for service in environment-monitor occupancy-monitor alert-notifier sensors-data-collector home-dashboard; do
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
<minikube-ip> alert-notifier.local
<minikube-ip> sensors-data-collector.local
<minikube-ip> home-dashboard.local
```

Backend health checks go through Ingress:

```bash
curl -H 'Host: environment-monitor.local' "http://$MINIKUBE_IP/live"
curl -H 'Host: occupancy-monitor.local' "http://$MINIKUBE_IP/live"
curl -H 'Host: alert-notifier.local' "http://$MINIKUBE_IP/live"
curl -H 'Host: sensors-data-collector.local' "http://$MINIKUBE_IP/live"
```

Open the dashboard at `http://home-dashboard.local` after adding the hosts
entry. No `kubectl port-forward` is required.

## Root commands

Run commands from the repository root:

```bash
make test
make generate
make image IMAGE=environment-monitor:dev
make images
make build SERVICE=environment-monitor
make build-all
make build-changes
make helm-lint
make helm-template
make helm-package
```

`make build SERVICE=<service>` builds one service, including its tests, OpenAPI generation where applicable, Helm lint, Helm packaging, and container image. `make build-all` runs the complete repository build and packages every chart. `make build-changes` builds and packages only services affected by the current Git changes; use `DIFF_BASE=<git-ref>` to choose the comparison base.

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
ghcr.io/<owner>/alert-notifier:latest
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
oci://ghcr.io/<owner>/charts/alert-notifier
oci://ghcr.io/<owner>/charts/sensors-data-collector
oci://ghcr.io/<owner>/charts/home-dashboard
```

The chart version is `0.1.0-ci.<github-run-number>` and its `appVersion` is the
image commit SHA. Install a published chart with the matching image tag:

```bash
helm upgrade --install alert-notifier \
  oci://ghcr.io/<owner>/charts/alert-notifier \
  --version 0.1.0-ci.<github-run-number> \
  --set image.repository=ghcr.io/<owner>/alert-notifier \
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
