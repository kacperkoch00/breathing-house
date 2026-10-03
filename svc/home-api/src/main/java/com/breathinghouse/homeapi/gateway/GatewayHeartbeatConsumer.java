package com.breathinghouse.homeapi.gateway;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class GatewayHeartbeatConsumer {

    private static final Logger log = LoggerFactory.getLogger(GatewayHeartbeatConsumer.class);

    private final GatewayHeartbeatParser parser;
    private final GatewayHeartbeatRepository repository;
    private final Duration retryDelay;

    public GatewayHeartbeatConsumer(
            GatewayHeartbeatParser parser,
            GatewayHeartbeatRepository repository,
            @Value("${home-api.kafka.retry-delay}") Duration retryDelay) {
        this.parser = parser;
        this.repository = repository;
        this.retryDelay = retryDelay;
    }

    @KafkaListener(
            topics = "${home-api.kafka.status-topic}",
            groupId = "${home-api.kafka.status-consumer-group-id}")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        GatewayHeartbeat heartbeat;
        try {
            byte[] value = record.value() == null
                    ? new byte[0]
                    : record.value().getBytes(StandardCharsets.UTF_8);
            heartbeat = parser.parse(value, record.topic(), record.partition(), record.offset());
        } catch (NonRetryableHeartbeatException ex) {
            log.warn(
                    "Skipping non-retryable gateway STATUS record topic={} partition={} offset={}: {}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    ex.getMessage());
            acknowledgment.acknowledge();
            return;
        }

        while (true) {
            try {
                repository.insert(heartbeat);
                acknowledgment.acknowledge();
                log.debug(
                        "Persisted gateway heartbeat topic={} partition={} offset={} gatewayId={}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        heartbeat.gatewayId());
                return;
            } catch (DataAccessException ex) {
                log.error(
                        "Failed to persist gateway heartbeat topic={} partition={} offset={}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        ex);
                if (!waitRetry()) {
                    return;
                }
            }
        }
    }

    private boolean waitRetry() {
        try {
            Thread.sleep(retryDelay.toMillis());
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
