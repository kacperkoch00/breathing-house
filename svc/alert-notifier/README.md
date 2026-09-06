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
make image SERVICE=alert-notifier IMAGE=ghcr.io/<owner>/alert-notifier:0.1.0
docker push ghcr.io/<owner>/alert-notifier:0.1.0
```

OpenAPI source is in `openapi.yaml`. Springdoc also exposes `/v3/api-docs` and
`/swagger-ui.html` while the service is running.

## Environment Variables

The application can be fully configured at runtime using the following environment variables. If an environment variable is omitted, the service automatically falls back to its default local development value.

| Environment Variable | Description | Local Default Value | Java Property Mapping |
| :--- | :--- | :--- | :--- |
| `MQTT_BROKER_IP` | IPv4 address or hostname of the Pi Gateway broker | `localhost` | `mqtt.broker.ip` |
| `MQTT_BROKER_PORT` | Network port for the MQTT 5 broker instance | `1883` | `mqtt.broker.port` |
| `MQTT_CLIENT_ID` | Base identifier string for this microservice node | `alert-notifier` | `mqtt.client.id` |
| `MQTT_CONSUMER_TOPICS` | Comma-separated list of target sensor wildcards | `home/+/room,home/+/air,home/+/opening,home/+/presence,home/gateway/status` | `mqtt.consumer.topics` |
| `MQTT_INITIAL_DELAY_MS` | Starting time frame for back-off reconnect tries | `1000` | `mqtt.initial.delay.ms` |
| `MQTT_MAX_DELAY_MS` | Maximum duration gap allowed between retry attempts | `60000` | `mqtt.max.delay.ms` |

### Setting Environment Variables in Kubernetes

To customize these parameters within your Kubernetes deployment manifest (`deployment.yaml`), map the environment variables into your container spec block:

```yaml
spec:
  containers:
  - name: alert-notifier
    image: ghcr.io/<owner>/alert-notifier:0.1.0
    env:
    - name: MQTT_BROKER_IP
      value: "mosquitto-service.gateway.svc.cluster.local"
    - name: MQTT_CONSUMER_TOPICS
      value: "home/+/room,home/+/air,home/+/opening,home/+/presence,home/gateway/status"
    - name: MQTT_MAX_DELAY_MS
      value: "30000"
```

## Kubernetes

From the repository root, install the chart with an image from your container
registry:

```bash
helm upgrade --install alert-notifier deploy/helm/alert-notifier \
	--set image.repository=ghcr.io/<owner>/alert-notifier \
	--set image.tag=0.1.0 \
	--set image.pullPolicy=IfNotPresent

kubectl rollout status deployment/alert-notifier-alert-notifier
```

The chart configures port `8082` and uses `/live` and `/ready` for Kubernetes
probes. Access it locally with:

```bash
kubectl port-forward service/alert-notifier-alert-notifier 8082:8082
curl http://localhost:8082/live
```
