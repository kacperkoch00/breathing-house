.PHONY: test test-go test-java test-dashboard generate generate-service helm-lint helm-template helm-package helm-package-all image images build build-all build-changes k8s-start k8s-stop k8s-load k8s-deploy k8s-observability k8s-mqtt

SERVICE ?= environment-monitor
SERVICE_DIR := svc/$(SERVICE)
CHART_DIR ?= deploy/helm/$(SERVICE)
IMAGE ?= localhost/$(SERVICE):dev
IMAGE_REPOSITORY ?= localhost/$(SERVICE)
OPENAPI_SERVICES ?= environment-monitor occupancy-monitor
JAVA_SERVICES := alert-notifier sensors-data-collector
IMAGE_SERVICES := environment-monitor occupancy-monitor alert-notifier sensors-data-collector home-dashboard
SERVICES := environment-monitor occupancy-monitor alert-notifier sensors-data-collector home-dashboard
DIFF_BASE ?= HEAD~1
K8S_RELEASE ?= $(SERVICE)

test: test-go test-java test-dashboard

test-go:
	@find svc -name go.mod -exec dirname {} \; | while IFS= read -r dir; do \
		echo "==> testing $$dir"; \
		(cd "$$dir" && go test ./...); \
	done

test-java:
	@for service in $(JAVA_SERVICES); do \
		echo "==> testing svc/$$service"; \
		(cd svc/$$service && mvn -B test); \
	done

test-dashboard:
	@echo "==> building svc/home-dashboard"; \
	(cd svc/home-dashboard && npm install && npm run build)

generate:
	@for service in $(OPENAPI_SERVICES); do \
		echo "==> generating $$service"; \
		$(MAKE) -C svc/$$service generate; \
	done

generate-service:
	@if test "$(SERVICE)" = "environment-monitor" || test "$(SERVICE)" = "occupancy-monitor"; then \
		$(MAKE) -C $(SERVICE_DIR) generate; \
	fi

helm-lint:
	@for chart in deploy/helm/*; do \
		if test -f "$$chart/Chart.yaml"; then helm lint "$$chart"; fi; \
	done

helm-template:
	helm template $(SERVICE) $(CHART_DIR)

helm-package:
	mkdir -p dist/charts
	helm package $(CHART_DIR) --destination dist/charts

helm-package-all:
	mkdir -p dist/charts
	@for chart in deploy/helm/*; do \
		if test -f "$$chart/Chart.yaml"; then helm package "$$chart" --destination dist/charts; fi; \
	done

image:
	podman build -t $(IMAGE) $(SERVICE_DIR)

images:
	@for service in $(IMAGE_SERVICES); do \
		echo "==> building $$service"; \
		podman build -t localhost/$$service:dev svc/$$service; \
	done

build:
	@if test "$(SERVICE)" = "all"; then \
		for service in $(SERVICES); do \
			echo "==> building $$service"; \
			$(MAKE) build SERVICE=$$service IMAGE=localhost/$$service:dev; \
		done; \
	else \
		case "$(SERVICE)" in \
			environment-monitor|occupancy-monitor) \
				$(MAKE) generate-service; \
				$(MAKE) -C $(SERVICE_DIR) test; \
				helm lint $(CHART_DIR); \
				$(MAKE) helm-package SERVICE=$(SERVICE); \
				podman build -t $(IMAGE) $(SERVICE_DIR);; \
			alert-notifier|sensors-data-collector) \
				(cd $(SERVICE_DIR) && mvn -B test package); \
				helm lint $(CHART_DIR); \
				$(MAKE) helm-package SERVICE=$(SERVICE); \
				podman build -t $(IMAGE) $(SERVICE_DIR);; \
			home-dashboard) \
				(cd $(SERVICE_DIR) && npm install && npm run build); \
				helm lint $(CHART_DIR); \
				$(MAKE) helm-package SERVICE=$(SERVICE); \
				podman build -t $(IMAGE) $(SERVICE_DIR);; \
			*) echo "Unknown SERVICE='$(SERVICE)'. Choose one of: $(SERVICES) all"; exit 1;; \
		esac; \
	fi

build-all: generate test helm-lint helm-package-all images

build-changes:
	@changed_services=$$( \
		{ git diff --name-only $(DIFF_BASE)...HEAD 2>/dev/null || true; \
		  git diff --name-only HEAD; \
		  git ls-files --others --exclude-standard; } | \
		awk -F/ '/^svc\// { print $$2 } /^deploy\/helm\// { print $$3 }' | \
		sort -u | \
		awk 'BEGIN { split("$(SERVICES)", known, " "); for (i in known) valid[known[i]]=1 } valid[$$0] { print $$0 }' \
	); \
	if test -z "$$changed_services"; then \
		echo "No changed services detected"; \
	else \
		for service in $$changed_services; do \
			echo "==> building changed service $$service"; \
			$(MAKE) build SERVICE=$$service IMAGE=localhost/$$service:dev; \
		done; \
	fi

k8s-start:
	minikube start --driver=podman
	minikube addons enable ingress

k8s-stop:
	minikube stop

k8s-load:
	@if test "$(SERVICE)" = "all"; then \
		for service in $(SERVICES); do \
			echo "==> loading image for $$service"; \
			$(MAKE) k8s-load SERVICE=$$service IMAGE=localhost/$$service:dev; \
		done; \
	else \
		if podman image exists "$(IMAGE)" 2>/dev/null; then \
			podman save -o /tmp/$(SERVICE).tar "$(IMAGE)"; \
			minikube image load /tmp/$(SERVICE).tar; \
			rm -f /tmp/$(SERVICE).tar; \
		else \
			echo "Image '$(IMAGE)' not found locally; run 'make build SERVICE=$(SERVICE)' first."; \
			exit 1; \
		fi; \
	fi

k8s-deploy:
	@if test "$(SERVICE)" = "all"; then \
		for service in $(SERVICES); do \
			echo "==> deploying $$service"; \
			$(MAKE) k8s-deploy SERVICE=$$service K8S_RELEASE=$$service IMAGE_REPOSITORY=localhost/$$service; \
		done; \
	else \
		helm upgrade --install $(K8S_RELEASE) deploy/helm/$(SERVICE) \
			--set fullnameOverride=$(SERVICE) \
			--set image.repository=$(IMAGE_REPOSITORY) \
			--set image.tag=dev \
			--set image.pullPolicy=IfNotPresent \
			--set ingress.enabled=true; \
		kubectl rollout restart deployment/$(SERVICE); \
		kubectl rollout status deployment/$(SERVICE) --timeout=120s; \
	fi

k8s-mqtt: k8s-start
	helm upgrade --install mqtt-broker deploy/helm/mqtt-broker
	kubectl rollout status deployment/mqtt-broker --timeout=120s

k8s-observability: k8s-start
	@kubectl create namespace observability --dry-run=client -o yaml | kubectl apply -f -
	helm repo add grafana https://grafana.github.io/helm-charts
	helm repo add grafana-community https://grafana-community.github.io/helm-charts
	helm repo update
	helm upgrade --install loki grafana-community/loki \
		--namespace observability \
		-f deploy/observability/loki-values.yaml
	helm upgrade --install alloy grafana/alloy \
		--namespace observability \
		-f deploy/observability/alloy-values.yaml
	helm upgrade --install grafana grafana-community/grafana \
		--namespace observability \
		-f deploy/observability/grafana-values.yaml