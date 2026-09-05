FROM docker.io/library/golang:1.25-alpine AS build

WORKDIR /src
COPY go.mod ./
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/occupancy-monitor ./cmd/server

FROM docker.io/library/alpine:3.22
RUN addgroup -S -g 10001 app && adduser -S -D -u 10001 -G app app
COPY --from=build /out/occupancy-monitor /usr/local/bin/occupancy-monitor
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["/usr/local/bin/occupancy-monitor"]
