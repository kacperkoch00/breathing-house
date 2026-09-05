# Kubernetes on WSL

This repository uses Minikube with the Podman driver and the NGINX Ingress
controller. The commands below install client tools in `~/.local/bin`, so that
part does not require `sudo`.

Minikube's Podman driver may require rootful Podman access through `sudo`. If
your WSL user cannot run `sudo podman`, use Docker Desktop with WSL integration
and start Minikube with `--driver=docker` instead.

## Install tools

Run in your WSL distribution:

```bash
mkdir -p "$HOME/.local/bin"

curl -fsSL "https://dl.k8s.io/release/$(curl -fsSL https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl" \
  -o "$HOME/.local/bin/kubectl"

curl -fsSL https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64 \
  -o "$HOME/.local/bin/minikube"

chmod +x "$HOME/.local/bin/kubectl" "$HOME/.local/bin/minikube"
export PATH="$HOME/.local/bin:$PATH"
```

Add the `export PATH` line to `~/.bashrc` if it is not already on your PATH.

Check the prerequisites:

```bash
kubectl version --client
minikube version
podman info
```

## Start Kubernetes

From the repository root:

```bash
make k8s-start
kubectl get nodes
kubectl get pods -A
```

The `k8s-start` target starts Minikube with Podman and enables the NGINX Ingress
controller. It is safe to run again after the cluster already exists. If the
Podman driver reports a sudo or rootful-access error, start it with Docker
Desktop instead:

```bash
minikube start --driver=docker
minikube addons enable ingress
```

GitHub Actions runs the equivalent integration test automatically with a
temporary Kind cluster. It installs the NGINX Ingress controller, deploys the
service chart, and checks the endpoint through the Ingress Host header rather
than using port-forwarding.

## Build and deploy a service

Build the image, load it into Minikube, and install the service chart with
Ingress enabled:

```bash
make build SERVICE=environment-monitor
make k8s-load SERVICE=environment-monitor
make k8s-deploy SERVICE=environment-monitor
```

For a registry image published by GitHub Actions, deploy with:

```bash
helm upgrade --install environment-monitor deploy/helm/environment-monitor \
  --set image.repository=ghcr.io/<owner>/environment-monitor \
  --set image.tag=latest \
  --set image.pullPolicy=IfNotPresent \
  --set imagePullSecrets[0].name=ghcr-pull-secret
```

Omit `imagePullSecrets[0].name` if the package is public. For a private
package, create the secret first with a GitHub token that can read packages:

```bash
kubectl create secret docker-registry ghcr-pull-secret \
  --docker-server=ghcr.io \
  --docker-username=<github-username> \
  --docker-password=<github-token> \
  --docker-email=<email>
```

Repeat with `occupancy-monitor`, `alert-notifier`, `sensors-data-collector`, or
`home-dashboard` as needed.

## Access through Ingress

Get the Minikube IP:

```bash
minikube ip
```

The default hosts are:

```text
environment-monitor.local
occupancy-monitor.local
alert-notifier.local
sensors-data-collector.local
home-dashboard.local
```

For local access without editing `/etc/hosts`, send the host header directly to
the Minikube IP:

```bash
MINIKUBE_IP="$(minikube ip)"
curl -H 'Host: environment-monitor.local' "http://${MINIKUBE_IP}/live"
```

To use browser-friendly hostnames, add the relevant entry to `/etc/hosts`:

```text
<minikube-ip> environment-monitor.local
```

The dashboard is available at `http://home-dashboard.local` after adding its
hosts entry.

## Stop or remove the cluster

```bash
make k8s-stop
minikube delete
```
