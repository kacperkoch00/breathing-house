package com.breathinghouse.sensorsdatacollector.consumer;

import com.hivemq.client.mqtt.MqttClientConfig;
import com.hivemq.client.mqtt.lifecycle.MqttClientConnectedContext;
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext;

import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MqttInfrastructureConfigTest {

    @Mock
    private SensorDataConsumerConfig config;

    @InjectMocks
    private MqttInfrastructureConfig mqttInfrastructureConfig;

    @Test
    void shouldConfigureHiveMqClientWithProperties() {
        String clientId = "sensors-data-collector";
        String brokerIp = "192.168.1.50";
        Integer brokerPort = 1883;
        Integer initialDelayMs = 1000;
        Integer maxDelayMs = 60000;

        when(config.getBrokerIp()).thenReturn(brokerIp);
        when(config.getBrokerPort()).thenReturn(brokerPort);
        when(config.getClientId()).thenReturn(clientId);
        when(config.getInitialDelayMs()).thenReturn(initialDelayMs);
        when(config.getMaxDelayMs()).thenReturn(maxDelayMs);

        Mqtt5AsyncClient client = mqttInfrastructureConfig.hiveMqClient(config);

        assertNotNull(client, "Generated client bean must not be null");

        MqttClientConfig clientConfig = client.getConfig();

        assertEquals(brokerIp, clientConfig.getServerHost());
        assertEquals(brokerPort, clientConfig.getServerPort());

        String assignedClientId = clientConfig.getClientIdentifier()
                .map(Object::toString)
                .orElse("");

        assertTrue(assignedClientId.startsWith(clientId),
                "Client ID should start with your configured property base string value");

        assertTrue(clientConfig.getAutomaticReconnect().isPresent(),
                "Automatic reconnect back-off subsystem should be registered");

        assertFalse(clientConfig.getDisconnectedListeners().isEmpty(), "Disconnected listeners should not be empty");
        assertFalse(clientConfig.getConnectedListeners().isEmpty(), "Connected listeners should not be empty");
    }

    @Test
    void shouldHandleConnectedEvent() {
        var connectContext = mock(MqttClientConnectedContext.class);
        mqttInfrastructureConfig.onConnected(connectContext);
    }

    @Test
    void shouldHandleDisconnectedEvent() {
        var context = mock(MqttClientDisconnectedContext.class);
        var cause = mock(Throwable.class);

        when(context.getCause()).thenReturn(cause);
        when(cause.getMessage()).thenReturn("test");

        mqttInfrastructureConfig.onDisconnected(context);
    }
}
