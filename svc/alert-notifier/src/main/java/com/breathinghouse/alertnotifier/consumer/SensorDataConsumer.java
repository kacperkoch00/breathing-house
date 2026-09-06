package com.breathinghouse.alertnotifier.consumer;

import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5Subscribe;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5Subscription;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.suback.Mqtt5SubAck;
import jakarta.annotation.PostConstruct;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

@Component
public class SensorDataConsumer {

    private static final Logger log = LoggerFactory.getLogger(SensorDataConsumer.class);

    private final SensorDataConsumerConfig sensorDataConsumerConfig;
    private final MessageChannel mqttInputChannel;
    private final Mqtt5AsyncClient hiveMqClient;

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
        log.info("Initiating non-blocking background connection to the broker...");

        hiveMqClient.connect()
                .thenCompose(connAck -> subscribe())
                .exceptionally(throwable -> {
                    log.error("MQTT Connection/Subscription failed: {}", throwable.getMessage(), throwable);
                    return null;
                });
    }

    private @NonNull CompletableFuture<Mqtt5SubAck> subscribe() {
        log.info("Connected to broker via HiveMQ MQTT 5!");

        Mqtt5Subscribe subMessage = Mqtt5Subscribe.builder()
                .addSubscriptions(sensorDataConsumerConfig.getConsumerTopics().stream()
                        .map(topic -> Mqtt5Subscription.builder().topicFilter(topic).build())
                        .toList())
                .build();

        return hiveMqClient.subscribe(subMessage, publish -> {
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
}