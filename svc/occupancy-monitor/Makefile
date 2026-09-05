.PHONY: generate test run image

IMAGE ?= occupancy-monitor:dev

generate:
	go run github.com/oapi-codegen/oapi-codegen/v2/cmd/oapi-codegen@latest --config oapi-codegen.yaml openapi.yaml

test:
	go test ./...

run:
	go run ./cmd/server

image:
	docker build -t $(IMAGE) .
