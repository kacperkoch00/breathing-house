# Sensors Data Collector

Minimal Spring Boot 3 service running on Java 21. It currently exposes health
endpoints while sensor ingestion is being built.

```bash
mvn -B test
mvn spring-boot:run
curl http://localhost:8083/live
curl http://localhost:8083/ready
```

The service listens on port `8083` by default. From the repository root, build
its image and Helm chart with:

```bash
make build SERVICE=sensors-data-collector
```

OpenAPI source is in `openapi.yaml`. Springdoc also exposes `/v3/api-docs` and
`/swagger-ui.html` while the service is running.