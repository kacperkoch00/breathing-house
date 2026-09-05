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
make build SERVICE=home-dashboard
```

The production container serves the dashboard on port `8080`. Its OpenAPI file
is a placeholder until the dashboard has a backend API.