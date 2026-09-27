package com.breathinghouse.sensorsdatacollector.consumer;

import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.lifecycle.Mqtt5ClientConnectedContext;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5Subscribe;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5Subscription;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class SensorDataConsumer {

    private static final Logger log = LoggerFactory.getLogger(SensorDataConsumer.class);

    private final SensorDataConsumerConfig sensorDataConsumerConfig;
    private final MessageChannel mqttInputChannel;
    private final Mqtt5AsyncClient hiveMqClient;
    private final AtomicBoolean publishesRegistered = new AtomicBoolean(false);

    public SensorDataConsumer(
            final SensorDataConsumerConfig mqttProperties,
            final MessageChannel mqttInputChannel,
            final Mqtt5AsyncClient hiveMqClient) {

        this.sensorDataConsumerConfig = mqttProperties;
        this.mqttInputChannel = mqttInputChannel;
        this.hiveMqClient = hiveMqClient;
    }

    @PostConstruct
    public void startMqttSubscription() {
        log.debug("Initiating non-blocking background connection to the broker...");

        registerPublishesOnce();

        hiveMqClient.connect()
                .exceptionally(throwable -> {
                    log.error("MQTT Connection failed: {}", throwable.getMessage(), throwable);
                    return null;
                });
    }

    /**
     * Invoked on every successful MQTT connect (initial and reconnect).
     * Resubscribes only when the broker reports no session, so publish
     * callbacks are never registered twice.
     */
    public void onConnected(Mqtt5ClientConnectedContext context) {
        if (context.getConnAck().isSessionPresent()) {
            log.debug("MQTT session present; skipping resubscribe");
            return;
        }

        subscribe();
    }

    private void registerPublishesOnce() {
        if (!publishesRegistered.compareAndSet(false, true)) {
            return;
        }

        hiveMqClient.publishes(MqttGlobalPublishFilter.ALL, publish -> {
            String topic = publish.getTopic().toString();
            String payload = new String(
                    publish.getPayloadAsBytes(),
                    StandardCharsets.UTF_8
            );

            mqttInputChannel.send(
                    MessageBuilder.withPayload(payload)
                            .setHeader("mqtt_topic", topic)
                            .build()
            );
        });
    }

    void subscribe() {
        log.info("Subscribing to MQTT topics with QoS {}", sensorDataConsumerConfig.getQos());

        MqttQos qos = MqttQos.fromCode(sensorDataConsumerConfig.getQos());
        if (qos == null) {
            throw new IllegalStateException("Invalid MQTT QoS: " + sensorDataConsumerConfig.getQos());
        }

        Mqtt5Subscribe subMessage = Mqtt5Subscribe.builder()
                .addSubscriptions(sensorDataConsumerConfig.getConsumerTopics().stream()
                        .map(topic -> Mqtt5Subscription.builder()
                                .topicFilter(topic)
                                .qos(qos)
                                .build())
                        .toList())
                .build();

        hiveMqClient.subscribe(subMessage)
                .exceptionally(throwable -> {
                    log.error("MQTT subscription failed: {}", throwable.getMessage(), throwable);
                    return null;
                });
    }
}
