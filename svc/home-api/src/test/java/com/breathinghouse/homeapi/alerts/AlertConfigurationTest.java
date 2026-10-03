package com.breathinghouse.homeapi.alerts;

import com.breathinghouse.homeapi.alerts.AlertConfiguration.Combinator;
import com.breathinghouse.homeapi.alerts.AlertConfiguration.RuleType;
import com.breathinghouse.homeapi.history.SensorType;
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
        assertThat(validated.alerts().getFirst().conditions()).isEmpty();
    }

    @Test
    void validatesCompositeUsingAirAndRoomConditions() throws Exception {
        AlertConfiguration.Validated validated = objectMapper.readValue("""
                {
                  "evaluationInterval": "10s",
                  "alerts": [{
                    "id": "poor-air-and-hot",
                    "enabled": true,
                    "type": "COMPOSITE",
                    "combinator": "ALL",
                    "for": "5m",
                    "severity": "WARNING",
                    "rooms": ["*"],
                    "message": "Poor conditions in {{roomId}}: {{values}}",
                    "conditions": [
                      {
                        "id": "co2",
                        "type": "THRESHOLD",
                        "source": "ENVIRONMENT",
                        "sensorType": "AIR",
                        "metric": "CO2",
                        "operator": "GREATER_THAN",
                        "threshold": 1200,
                        "maxDataAge": "2m"
                      },
                      {
                        "id": "temperature",
                        "type": "THRESHOLD",
                        "source": "ENVIRONMENT",
                        "sensorType": "ROOM",
                        "metric": "TEMPERATURE",
                        "operator": "GREATER_THAN",
                        "threshold": 28,
                        "maxDataAge": "2m"
                      }
                    ]
                  }]
                }
                """, AlertConfiguration.class).validate();

        AlertConfiguration.ValidatedRule rule = validated.alerts().getFirst();
        assertThat(rule.type()).isEqualTo(RuleType.COMPOSITE);
        assertThat(rule.combinator()).isEqualTo(Combinator.ALL);
        assertThat(rule.source()).isNull();
        assertThat(rule.conditions()).hasSize(2);
        assertThat(rule.conditions().getFirst().id()).isEqualTo("co2");
        assertThat(rule.conditions().getFirst().sensorType()).isEqualTo(SensorType.AIR);
        assertThat(rule.conditions().get(1).id()).isEqualTo("temperature");
        assertThat(rule.conditions().get(1).sensorType()).isEqualTo(SensorType.ROOM);
        assertThat(rule.conditions().getFirst().maxDataAge()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void rejectsDuplicateConditionIds() throws Exception {
        AlertConfiguration configuration = objectMapper.readValue(compositeWithConditions("""
                {
                  "id": "co2",
                  "type": "THRESHOLD",
                  "source": "ENVIRONMENT",
                  "sensorType": "AIR",
                  "metric": "CO2",
                  "operator": "GREATER_THAN",
                  "threshold": 1200,
                  "maxDataAge": "2m"
                },
                {
                  "id": "co2",
                  "type": "THRESHOLD",
                  "source": "ENVIRONMENT",
                  "sensorType": "ROOM",
                  "metric": "TEMPERATURE",
                  "operator": "GREATER_THAN",
                  "threshold": 28,
                  "maxDataAge": "2m"
                }
                """), AlertConfiguration.class);

        assertThatThrownBy(configuration::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate condition id");
    }

    @Test
    void rejectsFewerThanTwoConditions() throws Exception {
        AlertConfiguration configuration = objectMapper.readValue(compositeWithConditions("""
                {
                  "id": "co2",
                  "type": "THRESHOLD",
                  "source": "ENVIRONMENT",
                  "sensorType": "AIR",
                  "metric": "CO2",
                  "operator": "GREATER_THAN",
                  "threshold": 1200,
                  "maxDataAge": "2m"
                }
                """), AlertConfiguration.class);

        assertThatThrownBy(configuration::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least two conditions");
    }

    @Test
    void rejectsNestedCompositeConditions() throws Exception {
        AlertConfiguration configuration = objectMapper.readValue(compositeWithConditions("""
                {
                  "id": "co2",
                  "type": "THRESHOLD",
                  "source": "ENVIRONMENT",
                  "sensorType": "AIR",
                  "metric": "CO2",
                  "operator": "GREATER_THAN",
                  "threshold": 1200,
                  "maxDataAge": "2m"
                },
                {
                  "id": "nested",
                  "type": "COMPOSITE",
                  "source": "ENVIRONMENT",
                  "maxDataAge": "2m"
                }
                """), AlertConfiguration.class);

        assertThatThrownBy(configuration::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be COMPOSITE");
    }

    @Test
    void rejectsStaleDataConditions() throws Exception {
        AlertConfiguration configuration = objectMapper.readValue(compositeWithConditions("""
                {
                  "id": "co2",
                  "type": "THRESHOLD",
                  "source": "ENVIRONMENT",
                  "sensorType": "AIR",
                  "metric": "CO2",
                  "operator": "GREATER_THAN",
                  "threshold": 1200,
                  "maxDataAge": "2m"
                },
                {
                  "id": "stale",
                  "type": "STALE_DATA",
                  "source": "ENVIRONMENT",
                  "sensorType": "AIR",
                  "maxDataAge": "2m"
                }
                """), AlertConfiguration.class);

        assertThatThrownBy(configuration::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be STALE_DATA");
    }

    @Test
    void rejectsParentLeafFieldsOnComposite() throws Exception {
        AlertConfiguration configuration = objectMapper.readValue("""
                {
                  "evaluationInterval": "10s",
                  "alerts": [{
                    "id": "poor-air-and-hot",
                    "type": "COMPOSITE",
                    "combinator": "ALL",
                    "source": "ENVIRONMENT",
                    "for": "5m",
                    "severity": "WARNING",
                    "message": "bad",
                    "conditions": [
                      {
                        "id": "co2",
                        "type": "THRESHOLD",
                        "source": "ENVIRONMENT",
                        "sensorType": "AIR",
                        "metric": "CO2",
                        "operator": "GREATER_THAN",
                        "threshold": 1200,
                        "maxDataAge": "2m"
                      },
                      {
                        "id": "temperature",
                        "type": "THRESHOLD",
                        "source": "ENVIRONMENT",
                        "sensorType": "ROOM",
                        "metric": "TEMPERATURE",
                        "operator": "GREATER_THAN",
                        "threshold": 28,
                        "maxDataAge": "2m"
                      }
                    ]
                  }]
                }
                """, AlertConfiguration.class);

        assertThatThrownBy(configuration::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not set source");
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

    private static String compositeWithConditions(String conditions) {
        return """
                {
                  "evaluationInterval": "10s",
                  "alerts": [{
                    "id": "poor-air-and-hot",
                    "type": "COMPOSITE",
                    "combinator": "ALL",
                    "for": "5m",
                    "severity": "WARNING",
                    "message": "bad",
                    "conditions": [%s]
                  }]
                }
                """.formatted(conditions);
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
