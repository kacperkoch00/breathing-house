# Home Dashboard

Minimal React and Vite overview for Breathing House. The start page loads live
data from `home-api`: gateway status, rooms, latest environment readings, and
active alerts.

```bash
npm install
npm run dev
```

The development server is available at `http://localhost:5173`. Point it at a
running `home-api` with:

```bash
# optional; defaults to http://localhost:8082
cp .env.example .env
```

| Variable | Default | Purpose |
| --- | --- | --- |
| `VITE_HOME_API_BASE_URL` | `http://localhost:8082` | Base URL for `home-api` (no trailing slash) |

`home-api` CORS allows `http://localhost:5173` and `http://home-dashboard.local`.

Build the static site:

```bash
npm run build
```

## Container image

From the repository root (images are built with **podman**):

```bash
make image SERVICE=home-dashboard IMAGE=localhost/home-dashboard:dev
```

To publish to a registry, retag and push:

```bash
make image SERVICE=home-dashboard IMAGE=ghcr.io/<owner>/home-dashboard:0.1.0
docker push ghcr.io/<owner>/home-dashboard:0.1.0
```

The production container serves the dashboard on port `8080`. Bake
`VITE_HOME_API_BASE_URL` at build time when the browser should call a different
host (for example `http://home-api.local`).

## Kubernetes

Prefer Ingress (see the [root README](../../README.md#access-services-through-ingress)).
Open `http://home-dashboard.local` after adding the hosts entry.

From the repository root, install with a local image:

```bash
make build SERVICE=home-dashboard
make k8s-load SERVICE=home-dashboard IMAGE=localhost/home-dashboard:dev
make k8s-deploy SERVICE=home-dashboard
```

Or install the chart with an image from your container registry:

```bash
helm upgrade --install home-dashboard deploy/helm/home-dashboard \
  --set image.repository=ghcr.io/<owner>/home-dashboard \
  --set image.tag=0.1.0 \
  --set image.pullPolicy=IfNotPresent

kubectl rollout status deployment/home-dashboard
```

The chart exposes the dashboard on port `8080`. Fallback without Ingress:

```bash
kubectl port-forward service/home-dashboard 8080:8080
```

Then open `http://localhost:8080` in a browser.
