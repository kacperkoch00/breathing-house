package com.breathinghouse.homeapi.alerts;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertConfigurationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void validatesThresholdConfiguration() throws Exception {
        AlertConfiguration.Validated validated = objectMapper.readValue("""
                {
                  "evaluationInterval": "10s",
                  "alerts": [{
                    "id": "high-co2",
                    "type": "THRESHOLD",
                    "source": "ENVIRONMENT",
                    "sensorType": "AIR",
                    "metric": "CO2",
                    "operator": "GREATER_THAN",
                    "threshold": 1500,
                    "for": "5m",
                    "maxDataAge": "2m",
                    "severity": "WARNING",
                    "message": "CO2 is high"
                  }]
                }
                """, AlertConfiguration.class).validate();

        assertThat(validated.evaluationInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(validated.alerts()).hasSize(1);
        assertThat(validated.alerts().getFirst().holdDuration()).isEqualTo(Duration.ofMinutes(5));
        assertThat(validated.alerts().getFirst().enabled()).isTrue();
        assertThat(validated.alerts().getFirst().rooms()).containsExactly("*");
    }

    @Test
    void rejectsDuplicateRuleIds() throws Exception {
        AlertConfiguration configuration = objectMapper.readValue("""
                {
                  "evaluationInterval": "10s",
                  "alerts": [
                    {
                      "id": "duplicate",
                      "type": "STALE_DATA",
                      "source": "ENVIRONMENT",
                      "sensorType": "AIR",
                      "for": "5m",
                      "severity": "INFO",
                      "message": "stale"
                    },
                    {
                      "id": "duplicate",
                      "type": "STALE_DATA",
                      "source": "ENVIRONMENT",
                      "sensorType": "ROOM",
                      "for": "5m",
                      "severity": "INFO",
                      "message": "stale"
                    }
                  ]
                }
                """, AlertConfiguration.class);

        assertThatThrownBy(configuration::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate alert id");
    }

    @Test
    void reloadKeepsLastValidConfiguration(@TempDir Path tempDir) throws Exception {
        Path config = tempDir.resolve("alerts.json");
        Files.writeString(config, staleConfig("first", "10s"));

        AlertConfigurationLoader loader =
                new AlertConfigurationLoader(objectMapper, config.toString());
        loader.initialize();
        assertThat(loader.current().alerts().getFirst().id()).isEqualTo("first");

        Files.writeString(config, "{ invalid");
        loader.reloadIfChanged();
        assertThat(loader.current().alerts().getFirst().id()).isEqualTo("first");

        Files.writeString(config, staleConfig("second", "20s"));
        loader.reloadIfChanged();
        assertThat(loader.current().alerts().getFirst().id()).isEqualTo("second");
        assertThat(loader.current().evaluationInterval()).isEqualTo(Duration.ofSeconds(20));
    }

    private static String staleConfig(String id, String interval) {
        return """
                {
                  "evaluationInterval": "%s",
                  "alerts": [{
                    "id": "%s",
                    "type": "STALE_DATA",
                    "source": "ENVIRONMENT",
                    "sensorType": "AIR",
                    "for": "5m",
                    "severity": "INFO",
                    "message": "stale"
                  }]
                }
                """.formatted(interval, id);
    }
}
