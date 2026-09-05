# Home Dashboard

Minimal React and Vite dashboard start page for Breathing House.

```bash
npm install
npm run dev
```

The development server is available at `http://localhost:5173`.

Build the static site:

```bash
npm run build
```

From the repository root, build its image and Helm chart with:

```bash
make image SERVICE=home-dashboard IMAGE=ghcr.io/<owner>/home-dashboard:0.1.0
docker push ghcr.io/<owner>/home-dashboard:0.1.0
```

The production container serves the dashboard on port `8080`. Its OpenAPI file
is a placeholder until the dashboard has a backend API.

## Kubernetes

From the repository root, install the chart with an image from your container
registry:

```bash
helm upgrade --install home-dashboard deploy/helm/home-dashboard \
	--set image.repository=ghcr.io/<owner>/home-dashboard \
	--set image.tag=0.1.0 \
	--set image.pullPolicy=IfNotPresent

kubectl rollout status deployment/home-dashboard-home-dashboard
```

The chart exposes the dashboard on port `8080`. Access it locally with:

```bash
kubectl port-forward service/home-dashboard-home-dashboard 8080:8080
```

Then open `http://localhost:8080` in a browser.