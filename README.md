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
image build.

The root `SERVICE` variable selects the service for service-specific commands. For example:

```bash
make SERVICE=environment-monitor image IMAGE=ghcr.io/<owner>/environment-monitor:0.1.0
```

Each service image is defined by its Dockerfile under `svc/<service>`.
Each service has its own Kubernetes configuration under `deploy/helm/<service>`.

Install the chart into Kubernetes:

```bash
helm upgrade --install environment-monitor deploy/helm/environment-monitor
```

Install another service by changing the chart and release name, for example:

```bash
helm upgrade --install occupancy-monitor deploy/helm/occupancy-monitor
```
