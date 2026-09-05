# syntax=docker/dockerfile:1

FROM docker.io/library/golang:1.25-alpine AS build

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/environment-monitor ./cmd/server

FROM docker.io/library/alpine:3.22

RUN addgroup -S -g 10001 app && adduser -S -D -u 10001 -G app app

COPY --from=build /out/environment-monitor /usr/local/bin/environment-monitor

USER 10001:10001
EXPOSE 8080

ENTRYPOINT ["/usr/local/bin/environment-monitor"]
