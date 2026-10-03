#!/usr/bin/env bash
# Deploy Breathing House on desktop Minikube using newest GHCR images and local charts.
#
# Default MQTT is a host Mosquitto container (Raspberry Pi stand-in). Collector pods
# reach it via host.minikube.internal; publish from the host with
# scripts/publish-sensor-event.sh (localhost:1883).
#
# Optional env knobs (defaults shown):
#   OWNER=kacperkoch00 IMAGE_TAG=latest PULL_POLICY=Always
#   DRIVER=  GH_USER=$OWNER GHCR_TOKEN=
#   SKIP_HOSTS=0 WITH_OBSERVABILITY=0
#   MQTT_MODE=external MQTT_BROKER_IP=host.minikube.internal MQTT_BROKER_PORT=1883
#   MQTT_REQUIRE_REACHABLE=0 MQTT_CONTAINER_NAME=breathing-house-mosquitto
#   MQTT_IMAGE=eclipse-mosquitto:2.0.18
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
MQTT_MODE="${MQTT_MODE:-external}"
MQTT_BROKER_IP="${MQTT_BROKER_IP:-host.minikube.internal}"
MQTT_BROKER_PORT="${MQTT_BROKER_PORT:-1883}"
MQTT_REQUIRE_REACHABLE="${MQTT_REQUIRE_REACHABLE:-0}"
MQTT_CONTAINER_NAME="${MQTT_CONTAINER_NAME:-breathing-house-mosquitto}"
MQTT_IMAGE="${MQTT_IMAGE:-eclipse-mosquitto:2.0.18}"

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

validate_mqtt_mode() {
  case "${MQTT_MODE}" in
    in-cluster) ;;
    external)
      if [[ -z "${MQTT_BROKER_IP}" ]]; then
        die "MQTT_MODE=external requires MQTT_BROKER_IP (reachable FROM Minikube pods, not host localhost).

Default desktop path starts a host Mosquitto container and uses
MQTT_BROKER_IP=host.minikube.internal. Override if pods cannot reach that address.

Knobs: MQTT_MODE MQTT_BROKER_IP MQTT_BROKER_PORT MQTT_REQUIRE_REACHABLE
       MQTT_CONTAINER_NAME MQTT_IMAGE"
      fi
      ;;
    *)
      die "MQTT_MODE must be 'in-cluster' or 'external' (got: ${MQTT_MODE})"
      ;;
  esac
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

ensure_host_mqtt() {
  local runtime conf running
  runtime="$(detect_driver)"
  conf="${SCRIPT_DIR}/mosquitto/mosquitto.conf"
  [[ -f "${conf}" ]] || die "missing Mosquitto config: ${conf}"

  if "${runtime}" inspect "${MQTT_CONTAINER_NAME}" >/dev/null 2>&1; then
    running="$("${runtime}" inspect -f '{{.State.Running}}' "${MQTT_CONTAINER_NAME}")"
    if [[ "${running}" == "true" ]]; then
      log "reusing running host MQTT container ${MQTT_CONTAINER_NAME}"
      return
    fi
    log "starting stopped host MQTT container ${MQTT_CONTAINER_NAME}"
    "${runtime}" start "${MQTT_CONTAINER_NAME}" >/dev/null
    return
  fi

  log "starting host MQTT broker (${runtime} ${MQTT_IMAGE} as ${MQTT_CONTAINER_NAME})"
  "${runtime}" run -d \
    --name "${MQTT_CONTAINER_NAME}" \
    -p 1883:1883 \
    -v "${conf}:/mosquitto/config/mosquitto.conf:ro" \
    "${MQTT_IMAGE}" >/dev/null
}

probe_external_mqtt() {
  log "probing MQTT broker reachability from cluster (${MQTT_BROKER_IP}:${MQTT_BROKER_PORT})"
  if kubectl run mqtt-reachability-check \
    --rm -i --quiet --restart=Never \
    --image=busybox:1.36 \
    --namespace "${NAMESPACE}" \
    --command -- nc -z -w 5 "${MQTT_BROKER_IP}" "${MQTT_BROKER_PORT}"; then
    log "MQTT broker reachable from cluster"
    return
  fi

  local msg="MQTT broker ${MQTT_BROKER_IP}:${MQTT_BROKER_PORT} not reachable from Minikube pods"
  if [[ "${MQTT_REQUIRE_REACHABLE}" == "1" ]]; then
    die "${msg} (MQTT_REQUIRE_REACHABLE=1)"
  fi
  warn "${msg}"
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

install_postgres() {
  # Same as Makefile k8s-postgres (without re-running k8s-start / hardcoded driver).
  log "installing Postgres (schemas environment/occupancy)"
  kubectl apply -f deploy/k8s/postgres.yaml
  kubectl rollout status deployment/postgres --namespace "${NAMESPACE}" --timeout=180s
  kubectl exec deployment/postgres --namespace "${NAMESPACE}" -- \
    psql -U bh -d breathing_house -c \
    "SELECT nspname FROM pg_catalog.pg_namespace WHERE nspname IN ('environment','occupancy') ORDER BY 1;"
  kubectl exec deployment/postgres --namespace "${NAMESPACE}" -- \
    psql -U bh -d breathing_house -c \
    "SELECT table_schema, table_name FROM information_schema.tables WHERE table_schema IN ('environment','occupancy') ORDER BY 1, 2;"
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
    if [[ "${service}" == "sensors-data-collector" && "${MQTT_MODE}" == "external" ]]; then
      helm_args+=(
        --set "env.MQTT_BROKER_IP=${MQTT_BROKER_IP}"
        --set "env.MQTT_BROKER_PORT_NUMBER=${MQTT_BROKER_PORT}"
      )
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

# Curl Ingress with hard timeouts so a slow/unreachable minikube IP cannot hang forever.
# Minikube/WSL Ingress from the host is often flaky; retry generously.
curl_ingress() {
  local host="$1" path="$2" ip="$3"
  local attempt
  for attempt in $(seq 1 12); do
    if curl -sf --connect-timeout 2 --max-time 3 \
      -H "Host: ${host}" "http://${ip}${path}" >/dev/null; then
      return 0
    fi
    sleep 1
  done
  return 1
}

# Authoritative check: hit the ClusterIP from inside the cluster (avoids flaky host→Ingress path).
curl_in_cluster() {
  local service="$1" port="$2" path="$3"
  local pod="desktop-health-${service}"
  kubectl delete pod "${pod}" --namespace "${NAMESPACE}" --ignore-not-found >/dev/null 2>&1 || true
  if kubectl run "${pod}" \
    --rm -i --quiet --restart=Never \
    --image=curlimages/curl:8.10.1 \
    --namespace "${NAMESPACE}" \
    -- curl -sf --connect-timeout 2 --max-time 5 \
    "http://${service}.${NAMESPACE}.svc:${port}${path}" >/dev/null; then
    return 0
  fi
  return 1
}

check_one() {
  local host="$1" ingress_path="$2" service="$3" port="$4" cluster_path="$5" ip="$6"

  if curl_ingress "${host}" "${ingress_path}" "${ip}"; then
    printf '  ok  %s%s\n' "${host}" "${ingress_path}"
    return 0
  fi

  warn "Ingress check failed for ${host}${ingress_path}; trying in-cluster"
  if curl_in_cluster "${service}" "${port}" "${cluster_path}"; then
    printf '  ok  %s%s (in-cluster; Ingress flaky from host)\n' "${host}" "${ingress_path}"
    return 0
  fi

  printf '  FAIL %s%s\n' "${host}" "${ingress_path}" >&2
  return 1
}

check_health() {
  local ip
  ip="$(minikube ip)"

  log "waiting for Ingress controller"
  kubectl rollout status deployment/ingress-nginx-controller \
    --namespace ingress-nginx \
    --timeout=120s >/dev/null || warn "ingress-nginx rollout status failed; continuing"

  log "checking health through Ingress (in-cluster fallback if flaky)"

  check_one "environment-monitor.local" "/live" \
    "environment-monitor" "8080" "/live" "${ip}" || true
  check_one "occupancy-monitor.local" "/live" \
    "occupancy-monitor" "8081" "/live" "${ip}" || true
  check_one "alert-notifier.local" "/live" \
    "alert-notifier" "8082" "/live" "${ip}" || true
  check_one "sensors-data-collector.local" "/live" \
    "sensors-data-collector" "8083" "/live" "${ip}" || true
  check_one "home-dashboard.local" "/" \
    "home-dashboard" "8080" "/" "${ip}" || true
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

  if [[ "${MQTT_MODE}" == "external" ]]; then
    cat <<EOF

MQTT: host Mosquitto (Pi stand-in), MQTT_MODE=external
  Container: ${MQTT_CONTAINER_NAME}
  Publish from host: localhost:1883
  Collector pods use: ${MQTT_BROKER_IP}:${MQTT_BROKER_PORT}
  In-cluster mqtt-broker install was skipped.
  Example publish:
    ./scripts/publish-sensor-event.sh air
EOF
  else
    cat <<EOF

MQTT: MQTT_MODE=in-cluster (helm mqtt-broker)
EOF
  fi

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
  validate_mqtt_mode
  check_prerequisites
  start_cluster

  if [[ "${MQTT_MODE}" == "in-cluster" ]]; then
    install_mqtt
  else
    log "MQTT_MODE=external; ensuring host MQTT and skipping in-cluster mqtt-broker"
    ensure_host_mqtt
    probe_external_mqtt
  fi

  install_kafka
  install_postgres

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
