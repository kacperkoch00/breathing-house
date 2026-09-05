# Alert Notifier

Minimal Spring Boot 3 service running on Java 21. It currently exposes health
endpoints while alert delivery is being built.

```bash
mvn -B test
mvn spring-boot:run
curl http://localhost:8082/live
curl http://localhost:8082/ready
```

The service listens on port `8082` by default. From the repository root, build
its image and Helm chart with:

```bash
make build SERVICE=alert-notifier
```

OpenAPI source is in `openapi.yaml`. Springdoc also exposes `/v3/api-docs` and
`/swagger-ui.html` while the service is running.