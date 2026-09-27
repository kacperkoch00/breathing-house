#!/usr/bin/env bash
# Deploy Breathing House on desktop Minikube using newest GHCR images and local charts.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${ROOT_DIR}"

OWNER="${OWNER:-kacperkoch00}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
PULL_POLICY="${PULL_POLICY:-Always}"
NAMESPACE="${NAMESPACE:-default}"
GH_USER="${GH_USER:-${OWNER}}"
SKIP_HOSTS="${SKIP_HOSTS:-0}"
WITH_OBSERVABILITY="${WITH_OBSERVABILITY:-0}"

SERVICES=(
  environment-monitor
  occupancy-monitor
  alert-notifier
  sensors-data-collector
  home-dashboard
)

HOSTNAMES=(
  environment-monitor.local
  occupancy-monitor.local
  alert-notifier.local
  sensors-data-collector.local
  home-dashboard.local
)

log() { printf '==> %s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

require_bin() {
  command -v "$1" >/dev/null 2>&1 || die "required binary not found: $1"
}

detect_driver() {
  if [[ -n "${DRIVER:-}" ]]; then
    printf '%s\n' "${DRIVER}"
    return
  fi
  if command -v podman >/dev/null 2>&1 && podman info >/dev/null 2>&1; then
    printf 'podman\n'
    return
  fi
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    printf 'docker\n'
    return
  fi
  die "need a working podman or docker runtime (or set DRIVER=...)"
}

check_prerequisites() {
  require_bin kubectl
  require_bin minikube
  require_bin helm
  require_bin curl
  if [[ "${WITH_OBSERVABILITY}" == "1" ]]; then
    require_bin make
  fi

  local runtime_ok=0
  if command -v podman >/dev/null 2>&1 && podman info >/dev/null 2>&1; then
    runtime_ok=1
  fi
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    runtime_ok=1
  fi
  if [[ "${runtime_ok}" -ne 1 ]]; then
    die "need a working podman or docker runtime"
  fi
}

start_cluster() {
  local driver
  driver="$(detect_driver)"
  log "starting Minikube (driver=${driver})"
  minikube start --driver="${driver}"
  minikube addons enable ingress
}

install_mqtt() {
  # Same as Makefile k8s-mqtt (without re-running k8s-start / hardcoded driver).
  log "installing MQTT broker"
  helm upgrade --install mqtt-broker deploy/helm/mqtt-broker --namespace "${NAMESPACE}"
  kubectl rollout status deployment/mqtt-broker --namespace "${NAMESPACE}" --timeout=120s
}

install_kafka() {
  # Same as Makefile k8s-kafka (without re-running k8s-start / hardcoded driver).
  log "installing Kafka and topics"
  kubectl apply -f deploy/k8s/kafka.yaml
  kubectl rollout status deployment/kafka --namespace "${NAMESPACE}" --timeout=180s
  local topic
  for topic in sensor-data event-data status-data sensor-data-dlq; do
    kubectl exec deployment/kafka --namespace "${NAMESPACE}" -- \
      /opt/kafka/bin/kafka-topics.sh \
      --bootstrap-server localhost:9092 \
      --create \
      --if-not-exists \
      --topic "${topic}"
  done
}

configure_ghcr_pull() {
  if [[ -z "${GHCR_TOKEN:-}" ]]; then
    log "GHCR_TOKEN unset; assuming public GHCR images (no pull secret)"
    return
  fi

  log "logging in to GHCR and ensuring ghcr-pull-secret"
  echo "${GHCR_TOKEN}" | helm registry login ghcr.io \
    --username "${GH_USER}" \
    --password-stdin

  kubectl create secret docker-registry ghcr-pull-secret \
    --namespace "${NAMESPACE}" \
    --docker-server=ghcr.io \
    --docker-username="${GH_USER}" \
    --docker-password="${GHCR_TOKEN}" \
    --docker-email="${GH_EMAIL:-none@example.com}" \
    --dry-run=client -o yaml | kubectl apply -f -
}

deploy_services() {
  local service helm_args
  for service in "${SERVICES[@]}"; do
    log "deploying ${service} (ghcr.io/${OWNER}/${service}:${IMAGE_TAG})"
    helm_args=(
      upgrade --install "${service}" "deploy/helm/${service}"
      --namespace "${NAMESPACE}"
      --set "fullnameOverride=${service}"
      --set "image.repository=ghcr.io/${OWNER}/${service}"
      --set "image.tag=${IMAGE_TAG}"
      --set "image.pullPolicy=${PULL_POLICY}"
      --set ingress.enabled=true
      --set "ingress.hosts[0].host=${service}.local"
      --set "ingress.hosts[0].paths[0].path=/"
      --set "ingress.hosts[0].paths[0].pathType=Prefix"
    )
    if [[ -n "${GHCR_TOKEN:-}" ]]; then
      helm_args+=(--set "imagePullSecrets[0].name=ghcr-pull-secret")
    fi
    helm "${helm_args[@]}"
    kubectl rollout status "deployment/${service}" --namespace "${NAMESPACE}" --timeout=180s
  done
}

update_hosts() {
  local ip hosts_line marker
  ip="$(minikube ip)"
  hosts_line="${ip} ${HOSTNAMES[*]}"
  marker="breathing-house-desktop"

  if [[ "${SKIP_HOSTS}" == "1" ]]; then
    log "SKIP_HOSTS=1; add this line to /etc/hosts if needed:"
    printf '%s\n' "${hosts_line}"
    return
  fi

  log "updating /etc/hosts (sudo)"
  if ! sudo -n true >/dev/null 2>&1 && ! sudo true; then
    log "sudo failed; add this line to /etc/hosts manually:"
    printf '%s\n' "${hosts_line}"
    return
  fi

  # Replace any previous Breathing House desktop hosts line; keep other entries intact.
  if ! sudo sh -c "
    tmp=\$(mktemp)
    grep -v '# ${marker}' /etc/hosts > \"\${tmp}\" || true
    printf '%s # %s\n' '${hosts_line}' '${marker}' >> \"\${tmp}\"
    cat \"\${tmp}\" > /etc/hosts
    rm -f \"\${tmp}\"
  "; then
    log "hosts update failed; add this line to /etc/hosts manually:"
    printf '%s\n' "${hosts_line}"
  fi
}

check_health() {
  local ip service path
  ip="$(minikube ip)"
  log "checking health through Ingress"

  for service in environment-monitor occupancy-monitor alert-notifier sensors-data-collector; do
    path="/live"
    if curl -sf -H "Host: ${service}.local" "http://${ip}${path}" >/dev/null; then
      printf '  ok  %s%s\n' "${service}.local" "${path}"
    else
      printf '  FAIL %s%s\n' "${service}.local" "${path}" >&2
    fi
  done

  if curl -sf -H "Host: home-dashboard.local" "http://${ip}/" >/dev/null; then
    printf '  ok  home-dashboard.local/\n'
  else
    printf '  FAIL home-dashboard.local/\n' >&2
  fi
}

install_observability() {
  log "installing observability stack (Loki, Alloy, Prometheus, Grafana)"
  make k8s-observability

  log "waiting for Grafana and Prometheus rollouts"
  kubectl -n observability rollout status deployment/grafana --timeout=180s
  kubectl -n observability rollout status deployment/prometheus-server --timeout=180s

  log "checking Grafana /api/health"
  kubectl run grafana-health-check \
    --rm -i --quiet --restart=Never \
    --image=curlimages/curl:8.10.1 \
    -n observability \
    -- curl --fail --silent --show-error \
    "http://grafana.observability.svc.cluster.local/api/health"

  log "checking provisioned Breathing House dashboards (non-fatal)"
  dashboards="$(
    kubectl run grafana-dashboards-check \
      --rm -i --quiet --restart=Never \
      --image=curlimages/curl:8.10.1 \
      -n observability \
      -- curl --fail --silent --show-error \
      -u admin:admin \
      "http://grafana.observability.svc.cluster.local/api/search?query=Breathing%20House" \
      || true
  )"
  count="$(printf '%s' "${dashboards}" | { grep -o '"uid"' || true; } | wc -l | tr -d ' ')"
  if [[ "${count:-0}" -lt 5 ]]; then
    warn "expected ≥5 Breathing House dashboards, found ${count:-0}"
  else
    log "found ${count} Breathing House dashboards"
  fi
}

print_summary() {
  local ip
  ip="$(minikube ip)"
  cat <<EOF

Desktop setup complete (namespace=${NAMESPACE}, images=ghcr.io/${OWNER}/*:${IMAGE_TAG}).

Minikube IP: ${ip}

URLs (after /etc/hosts):
  http://environment-monitor.local/live
  http://occupancy-monitor.local/live
  http://alert-notifier.local/live
  http://sensors-data-collector.local/live
  http://home-dashboard.local/

Health via Ingress Host header:
  curl -H 'Host: environment-monitor.local' "http://${ip}/live"

Stop cluster:  make k8s-stop
Delete cluster: minikube delete
EOF

  if [[ "${WITH_OBSERVABILITY}" == "1" ]]; then
    cat <<EOF

Grafana (namespace=observability):
  login: admin / admin
  Loki and Prometheus datasources are pre-provisioned.
  Folder "Breathing House" dashboards:
    - Breathing House / Overview
    - Breathing House / Environment Monitor
    - Breathing House / Occupancy Monitor
    - Breathing House / Sensors Data Collector
    - Breathing House / Alert Notifier
  Port-forward:
    kubectl -n observability port-forward svc/grafana 3000:80
  Then open http://localhost:3000
EOF
  fi
}

main() {
  check_prerequisites
  start_cluster
  install_mqtt
  install_kafka

  if [[ "${WITH_OBSERVABILITY}" == "1" ]]; then
    install_observability
  fi

  configure_ghcr_pull
  deploy_services
  update_hosts
  check_health
  print_summary
}

main "$@"
