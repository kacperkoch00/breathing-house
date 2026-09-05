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

For a local Kubernetes cluster on WSL, see
[docs/kubernetes-wsl.md](docs/kubernetes-wsl.md). The short workflow is:

```bash
make k8s-start
make build SERVICE=environment-monitor
make k8s-load SERVICE=environment-monitor
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
