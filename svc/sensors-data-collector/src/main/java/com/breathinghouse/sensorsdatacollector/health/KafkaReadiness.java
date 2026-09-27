package com.breathinghouse.sensorsdatacollector.health;

import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class KafkaReadiness {

    private static final long TIMEOUT_MS = 2_000;

    private final AdminClient adminClient;

    public KafkaReadiness(AdminClient adminClient) {
        this.adminClient = adminClient;
    }

    public boolean isReachable() {
        try {
            adminClient.describeCluster().clusterId().get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
