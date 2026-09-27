package com.breathinghouse.sensorsdatacollector.consumer;

import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.datatypes.MqttTopic;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.lifecycle.Mqtt5ClientConnectedContext;
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAck;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
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
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SensorDataConsumerTest {

    @Mock private SensorDataConsumerConfig config;
    @Mock private Mqtt5AsyncClient hiveMqClient;
    @Mock private MessageChannel messageChannel;

    @InjectMocks
    private SensorDataConsumer sensorDataConsumer;

    @Test
    void shouldRegisterPublishesAndConnectOnStartup() {
        when(hiveMqClient.connect()).thenReturn(CompletableFuture.completedFuture(mock(Mqtt5ConnAck.class)));

        sensorDataConsumer.startMqttSubscription();

        verify(hiveMqClient).publishes(eq(MqttGlobalPublishFilter.ALL), any());
        verify(hiveMqClient).connect();
        verify(hiveMqClient, never()).subscribe(any(Mqtt5Subscribe.class));
    }

    @Test
    void shouldHandleConnectionFailuresGracefully() {
        CompletableFuture<Mqtt5ConnAck> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Pi Gateway Host Unreachable"));
        when(hiveMqClient.connect()).thenReturn(failedFuture);

        sensorDataConsumer.startMqttSubscription();

        verify(hiveMqClient).connect();
        verify(hiveMqClient, never()).subscribe(any(Mqtt5Subscribe.class));
    }

    @Test
    void shouldSubscribeWithConfiguredQosWhenSessionAbsent() {
        List<String> mockTopics = List.of("home/+/room", "home/+/air", "home/+/presence");
        when(config.getConsumerTopics()).thenReturn(mockTopics);
        when(config.getQos()).thenReturn(1);
        when(hiveMqClient.subscribe(any(Mqtt5Subscribe.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(Mqtt5SubAck.class)));

        Mqtt5ClientConnectedContext context = mock(Mqtt5ClientConnectedContext.class);
        Mqtt5ConnAck connAck = mock(Mqtt5ConnAck.class);
        when(context.getConnAck()).thenReturn(connAck);
        when(connAck.isSessionPresent()).thenReturn(false);

        sensorDataConsumer.onConnected(context);

        ArgumentCaptor<Mqtt5Subscribe> subscribeCaptor = ArgumentCaptor.forClass(Mqtt5Subscribe.class);
        verify(hiveMqClient).subscribe(subscribeCaptor.capture());

        Mqtt5Subscribe actualSubscribePacket = subscribeCaptor.getValue();
        assertEquals(mockTopics.size(), actualSubscribePacket.getSubscriptions().size());
        IntStream.range(0, mockTopics.size()).forEach(i -> {
            assertEquals(
                    mockTopics.get(i),
                    actualSubscribePacket.getSubscriptions().get(i).getTopicFilter().toString()
            );
            assertEquals(
                    MqttQos.AT_LEAST_ONCE,
                    actualSubscribePacket.getSubscriptions().get(i).getQos()
            );
        });
    }

    @Test
    void shouldSkipSubscribeWhenSessionPresent() {
        Mqtt5ClientConnectedContext context = mock(Mqtt5ClientConnectedContext.class);
        Mqtt5ConnAck connAck = mock(Mqtt5ConnAck.class);
        when(context.getConnAck()).thenReturn(connAck);
        when(connAck.isSessionPresent()).thenReturn(true);

        sensorDataConsumer.onConnected(context);

        verify(hiveMqClient, never()).subscribe(any(Mqtt5Subscribe.class));
    }

    @Test
    void shouldRegisterPublishesOnlyOnceAcrossReconnects() {
        when(hiveMqClient.connect()).thenReturn(CompletableFuture.completedFuture(mock(Mqtt5ConnAck.class)));
        when(config.getConsumerTopics()).thenReturn(List.of("home/+/room"));
        when(config.getQos()).thenReturn(1);
        when(hiveMqClient.subscribe(any(Mqtt5Subscribe.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(Mqtt5SubAck.class)));

        sensorDataConsumer.startMqttSubscription();
        sensorDataConsumer.startMqttSubscription();

        Mqtt5ClientConnectedContext context = mock(Mqtt5ClientConnectedContext.class);
        Mqtt5ConnAck connAck = mock(Mqtt5ConnAck.class);
        when(context.getConnAck()).thenReturn(connAck);
        when(connAck.isSessionPresent()).thenReturn(false);

        sensorDataConsumer.onConnected(context);
        sensorDataConsumer.onConnected(context);

        verify(hiveMqClient, times(1)).publishes(eq(MqttGlobalPublishFilter.ALL), any());
        verify(hiveMqClient, times(2)).subscribe(any(Mqtt5Subscribe.class));
    }

    @Test
    void shouldForwardMqttPayloadToSpringChannel() {
        when(hiveMqClient.connect()).thenReturn(CompletableFuture.completedFuture(mock(Mqtt5ConnAck.class)));
        sensorDataConsumer.startMqttSubscription();

        ArgumentCaptor<Consumer<Mqtt5Publish>> callbackCaptor = ArgumentCaptor.captor();
        verify(hiveMqClient).publishes(eq(MqttGlobalPublishFilter.ALL), callbackCaptor.capture());

        Mqtt5Publish mockPublish = mock(Mqtt5Publish.class);
        when(mockPublish.getPayloadAsBytes()).thenReturn("{\"test\":1}".getBytes(StandardCharsets.UTF_8));
        when(mockPublish.getTopic()).thenReturn(MqttTopic.of("home/kitchen/air"));

        callbackCaptor.getValue().accept(mockPublish);

        ArgumentCaptor<org.springframework.messaging.Message<?>> msgCaptor = ArgumentCaptor.captor();
        verify(messageChannel).send(msgCaptor.capture());
        assertEquals("{\"test\":1}", msgCaptor.getValue().getPayload());
    }
}
