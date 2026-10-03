# Home Dashboard

Minimal React and Vite overview for Breathing House. The start page (`/`) loads
live data from `home-api`: gateway status, rooms, latest environment readings,
and active alerts. Click an alert (overview summary/list or a room-detail row)
to open a read-only detail drawer (`GET /api/v1/alerts/{id}`), including the
rule snapshot. Room cards link to `/rooms/:roomId` for latest conditions,
active alerts for that room, a 24h temperature sparkline, and recent occupancy
events.

```bash
npm install
npm run dev
```

The development server is available at `http://localhost:5173`. By default the
browser calls **relative** `/api/...`; Vite proxies that to
`http://localhost:8082` (a local `home-api`). You only need `.env` if you want
an absolute cross-origin base URL instead:

```bash
# optional absolute override
cp .env.example .env
```

| Variable | Default | Purpose |
| --- | --- | --- |
| `VITE_HOME_API_BASE_URL` | _(empty — relative `/api`)_ | Optional absolute base URL for `home-api` (no trailing slash) |

`home-api` CORS still allows `http://localhost:5173` and
`http://home-dashboard.local` for direct cross-origin use, but the dashboard
no longer needs CORS when using the same-origin proxy.

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

The production container serves the dashboard on port `8080` and proxies
`/api/` to `HOME_API_UPSTREAM` (default `http://home-api:8082`). The image is
built **without** baking a cross-origin `VITE_HOME_API_BASE_URL`, so the
browser always uses same-origin `/api` unless you override at build time.

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

| Helm value | Env | Default | Purpose |
| --- | --- | --- | --- |
| `homeApiUpstream` | `HOME_API_UPSTREAM` | `http://home-api:8082` | Upstream base URL for the nginx `/api` proxy |

The chart exposes the dashboard on port `8080`. Because `/api` is proxied inside
the pod, **port-forwarding only the dashboard** is enough:

```bash
kubectl port-forward service/home-dashboard 8088:8080
```

Then open `http://localhost:8088` — Network requests should show same-origin
`/api/v1/...` succeeding without a separate `home-api` port-forward.
