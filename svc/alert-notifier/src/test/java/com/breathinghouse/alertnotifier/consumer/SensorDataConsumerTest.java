package com.breathinghouse.alertnotifier.consumer;

import com.hivemq.client.mqtt.datatypes.MqttTopic;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5Subscribe;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.suback.Mqtt5SubAck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessageChannel;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class SensorDataConsumerTest {
    @Mock private SensorDataConsumerConfig config;
    @Mock private Mqtt5AsyncClient hiveMqClient;
    @Mock private MessageChannel messageChannel;

    @InjectMocks
    private SensorDataConsumer sensorDataConsumer;

    @Test
    void shouldConnectAndSubscribeOnStartup() {
        List<String> mockTopics = List.of("home/+/room", "home/+/air", "home/+/occupation");
        when(config.getConsumerTopics()).thenReturn(mockTopics);

        CompletableFuture<Mqtt5ConnAck> connAckFuture = CompletableFuture.completedFuture(mock(Mqtt5ConnAck.class));
        CompletableFuture<Mqtt5SubAck> subAckFuture = CompletableFuture.completedFuture(mock(Mqtt5SubAck.class));

        when(hiveMqClient.connect()).thenReturn(connAckFuture);
        when(hiveMqClient.subscribe(any(Mqtt5Subscribe.class), any())).thenReturn(subAckFuture);

        sensorDataConsumer.startMqttSubscription();

        verify(hiveMqClient, times(1)).connect();

        ArgumentCaptor<Mqtt5Subscribe> subscribeCaptor = ArgumentCaptor.forClass(Mqtt5Subscribe.class);
        verify(hiveMqClient, times(1)).subscribe(subscribeCaptor.capture(), any());

        Mqtt5Subscribe actualSubscribePacket = subscribeCaptor.getValue();
        assertEquals(mockTopics.size(), actualSubscribePacket.getSubscriptions().size());
        IntStream.range(0, mockTopics.size()).forEach(i -> {
            assertEquals(
                    mockTopics.get(i),
                    actualSubscribePacket.getSubscriptions().get(i).getTopicFilter().toString()
            );
        });
    }

    @Test
    void shouldHandleConnectionFailuresGracefully() {
        CompletableFuture<Mqtt5ConnAck> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Pi Gateway Host Unreachable"));
        when(hiveMqClient.connect()).thenReturn(failedFuture);

        sensorDataConsumer.startMqttSubscription();

        verify(hiveMqClient, times(1)).connect();
        verify(hiveMqClient, never()).subscribe(any(Mqtt5Subscribe.class), any());
    }

    @Test
    void shouldForwardMqttPayloadToSpringChannel() {
        when(config.getConsumerTopics()).thenReturn(List.of("home/+/room"));
        when(hiveMqClient.connect()).thenReturn(CompletableFuture.completedFuture(null));
        when(hiveMqClient.subscribe(any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        sensorDataConsumer.startMqttSubscription();

        ArgumentCaptor<java.util.function.Consumer<com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish>> callbackCaptor = ArgumentCaptor.captor();
        verify(hiveMqClient).subscribe(any(), callbackCaptor.capture());

        var mockPublish = mock(com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish.class);
        when(mockPublish.getPayloadAsBytes()).thenReturn("{\"test\":1}".getBytes(StandardCharsets.UTF_8));
        when(mockPublish.getTopic()).thenReturn(MqttTopic.of("home/kitchen/air"));

        callbackCaptor.getValue().accept(mockPublish);

        ArgumentCaptor<org.springframework.messaging.Message<?>> msgCaptor = ArgumentCaptor.captor();
        verify(messageChannel).send(msgCaptor.capture());
        assertEquals("{\"test\":1}", msgCaptor.getValue().getPayload());
    }

}
