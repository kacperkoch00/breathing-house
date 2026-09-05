.PHONY: test test-go test-java test-dashboard generate generate-service helm-lint helm-template helm-package helm-package-all image images build build-all build-changes

SERVICE ?= environment-monitor
SERVICE_DIR := svc/$(SERVICE)
CHART_DIR ?= deploy/helm/$(SERVICE)
IMAGE ?= $(SERVICE):dev
OPENAPI_SERVICES ?= environment-monitor occupancy-monitor
JAVA_SERVICES := alert-notifier sensors-data-collector
IMAGE_SERVICES := environment-monitor occupancy-monitor alert-notifier sensors-data-collector home-dashboard
SERVICES := environment-monitor occupancy-monitor alert-notifier sensors-data-collector home-dashboard
DIFF_BASE ?= HEAD~1

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
	docker build -t $(IMAGE) $(SERVICE_DIR)

images:
	@for service in $(IMAGE_SERVICES); do \
		echo "==> building $$service"; \
		docker build -t $$service:dev svc/$$service; \
	done

build:
	@case "$(SERVICE)" in \
		environment-monitor|occupancy-monitor) \
			$(MAKE) generate-service; \
			$(MAKE) -C $(SERVICE_DIR) test; \
			helm lint $(CHART_DIR); \
			$(MAKE) helm-package SERVICE=$(SERVICE); \
			docker build -t $(IMAGE) $(SERVICE_DIR);; \
		alert-notifier|sensors-data-collector) \
			(cd $(SERVICE_DIR) && mvn -B test package); \
			helm lint $(CHART_DIR); \
			$(MAKE) helm-package SERVICE=$(SERVICE); \
			docker build -t $(IMAGE) $(SERVICE_DIR);; \
		home-dashboard) \
			(cd $(SERVICE_DIR) && npm install && npm run build); \
			helm lint $(CHART_DIR); \
			$(MAKE) helm-package SERVICE=$(SERVICE); \
			docker build -t $(IMAGE) $(SERVICE_DIR);; \
		*) echo "Unknown SERVICE='$(SERVICE)'. Choose one of: $(SERVICES)"; exit 1;; \
	esac

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
			$(MAKE) build SERVICE=$$service IMAGE=$$service:dev; \
		done; \
	fi
