package com.breathinghouse.sensorsdatacollector.consumer;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.lifecycle.MqttClientConnectedContext;
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext;
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient;
import com.hivemq.client.mqtt.mqtt5.lifecycle.Mqtt5ClientConnectedContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.messaging.MessageChannel;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Configuration
public class MqttInfrastructureConfig {

    private static final Logger log = LoggerFactory.getLogger(MqttInfrastructureConfig.class);

    @Bean
    public MessageChannel mqttInputChannel() {
        return new DirectChannel();
    }

    @Bean
    public Mqtt5AsyncClient hiveMqClient(
            SensorDataConsumerConfig config,
            ObjectProvider<SensorDataConsumer> sensorDataConsumer
    ) {
        return MqttClient.builder()
                .useMqttVersion5()
                .identifier(config.getClientId() + "-" + UUID.randomUUID())
                .serverHost(config.getBrokerIp())
                .serverPort(config.getBrokerPort())
                .automaticReconnect()
                .initialDelay(config.getInitialDelayMs(), TimeUnit.MILLISECONDS)
                .maxDelay(config.getMaxDelayMs(), TimeUnit.MILLISECONDS)
                .applyAutomaticReconnect()
                .addDisconnectedListener(this::onDisconnected)
                .addConnectedListener(context -> onConnected(context, sensorDataConsumer))
                .buildAsync();
    }

    void onDisconnected(MqttClientDisconnectedContext context) {
        log.debug(
                "Disconnected from MQTT broker. Cause: {}. Reconnect attempt scheduled.",
                context.getCause().getMessage()
        );
    }

    void onConnected(
            MqttClientConnectedContext context,
            ObjectProvider<SensorDataConsumer> sensorDataConsumer
    ) {
        log.debug("Successfully (re)connected to MQTT broker.");

        if (context instanceof Mqtt5ClientConnectedContext mqtt5Context) {
            SensorDataConsumer consumer = sensorDataConsumer.getIfAvailable();
            if (consumer != null) {
                consumer.onConnected(mqtt5Context);
            }
        }
    }
}
