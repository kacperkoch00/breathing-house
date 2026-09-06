package com.breathinghouse.alertnotifier.integrationtests;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ChannelInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
class MqttIntegrationIT {

    private static final String MQTT_HOST = "127.0.0.1";
    private static final int MQTT_PORT = 1883;
    private static final String TOPIC = "home/kitchen/air";
    private static final String PAYLOAD = "{\"temperature\":22.5}";

    @Autowired
    private MessageChannel mqttInputChannel;

    private Mqtt5AsyncClient publisher;

    private final AtomicReference<Message<?>> receivedMessage = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        ((DirectChannel) mqttInputChannel).addInterceptor(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
                receivedMessage.set(message);
                return message;
            }
        });

        publisher = MqttClient.builder()
                .useMqttVersion5()
                .identifier("integration-test-publisher")
                .serverHost(MQTT_HOST)
                .serverPort(MQTT_PORT)
                .buildAsync();

        publisher.connect().join();
    }

    @AfterEach
    void tearDown() {
        publisher.disconnect().join();
    }

    // as handler just logs for now, this is a stub to test integration
    @Test
    void shouldReceiveMqttMessageThroughWholeFlow() throws Exception {
        publisher.publishWith()
                .topic(TOPIC)
                .payload(PAYLOAD.getBytes(StandardCharsets.UTF_8))
                .send()
                .get(5, TimeUnit.SECONDS);

        await()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Message<?> message = receivedMessage.get();

                    assertThat(message).isNotNull();
                    assertThat(message.getPayload()).isEqualTo(PAYLOAD);
                    assertThat(message.getHeaders().get("mqtt_topic")).isEqualTo(TOPIC);
                });
    }
}