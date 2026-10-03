package com.breathinghouse.homeapi.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies Flyway V4/V5 against a real PostgreSQL. Opt-in: set TEST_DATABASE_URL to any database on a
 * server where the user may CREATE DATABASE, for example
 * {@code TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/postgres} (username and password come from
 * TEST_DATABASE_USERNAME / TEST_DATABASE_PASSWORD, default bh/bh). A scratch database is created per
 * test and dropped afterwards. Without the variable the test is skipped.
 */
class SensorRoomDomainMigrationTest {

    private static final String ADMIN_URL = System.getenv("TEST_DATABASE_URL");
    private static final String USERNAME = envOrDefault("TEST_DATABASE_USERNAME", "bh");
    private static final String PASSWORD = envOrDefault("TEST_DATABASE_PASSWORD", "bh");

    private JdbcTemplate admin;
    private String scratchDatabase;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource scratch;

    @BeforeEach
    void createScratchDatabase() throws Exception {
        assumeTrue(ADMIN_URL != null && !ADMIN_URL.isBlank(), "TEST_DATABASE_URL not set");

        admin = new JdbcTemplate(new DriverManagerDataSource(ADMIN_URL, USERNAME, PASSWORD));
        scratchDatabase = "home_api_migration_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + scratchDatabase);

        scratch = new DriverManagerDataSource(withDatabase(ADMIN_URL, scratchDatabase), USERNAME, PASSWORD);
        jdbc = new JdbcTemplate(scratch);
    }

    @AfterEach
    void dropScratchDatabase() {
        if (admin != null && scratchDatabase != null) {
            admin.execute("DROP DATABASE IF EXISTS " + scratchDatabase + " WITH (FORCE)");
        }
    }

    @Test
    void migratesHistoryIntoRoomsAndSensors() throws Exception {
        migrateTo("3");
        runScript("migration/pre-v4-history-schema.sql");
        runScript("migration/pre-v4-fixtures.sql");

        migrateTo(null);

        assertThat(jdbc.queryForList("SELECT room_id, name, description FROM home_api.room ORDER BY room_id"))
                .extracting(row -> row.get("room_id") + "|" + row.get("name") + "|" + row.get("description"))
                .containsExactly(
                        "attic|attic|null",
                        "bedroom|bedroom|null",
                        "garage|garage|null",
                        "kitchen|kitchen|null",
                        "living-room|Living Room|null");

        assertThat(jdbc.queryForList(
                "SELECT sensor_id, display_name, room_id FROM home_api.sensor ORDER BY sensor_id"))
                .extracting(row -> row.get("sensor_id") + "|" + row.get("display_name") + "|" + row.get("room_id"))
                .containsExactly(
                        "air-1|air-1|living-room",
                        "door-1|door-1|kitchen",
                        "hub-1|hub-1|kitchen",
                        "room-1|room-1|bedroom");
    }

    @Test
    void backfillsSensorIdKeepsLegacyRowsAndDropsDeviceId() throws Exception {
        migrateTo("3");
        runScript("migration/pre-v4-history-schema.sql");
        runScript("migration/pre-v4-fixtures.sql");
        int environmentRows = count("environment.environment_reading");
        int occupancyRows = count("occupancy.occupancy_event");

        migrateTo(null);

        assertThat(count("environment.environment_reading")).isEqualTo(environmentRows).isEqualTo(6);
        assertThat(count("occupancy.occupancy_event")).isEqualTo(occupancyRows).isEqualTo(3);

        List<Map<String, Object>> environment = jdbc.queryForList("""
                SELECT room_id, sensor_id FROM environment.environment_reading ORDER BY observed_at
                """);
        assertThat(environment)
                .extracting(row -> row.get("room_id") + "|" + row.get("sensor_id"))
                .containsExactly(
                        "attic|null",
                        "living-room|null",
                        "bedroom|hub-1",
                        "bedroom|air-1",
                        "bedroom|room-1",
                        "living-room|air-1");

        assertThat(jdbc.queryForList("""
                SELECT room_id, sensor_id FROM occupancy.occupancy_event ORDER BY observed_at
                """))
                .extracting(row -> row.get("room_id") + "|" + row.get("sensor_id"))
                .containsExactly("garage|null", "kitchen|door-1", "kitchen|hub-1");

        assertThat(columns("environment", "environment_reading")).doesNotContain("device_id");
        assertThat(columns("occupancy", "occupancy_event")).doesNotContain("device_id");
    }

    @Test
    void usesMetadataForRoomNamesThenDropsItWithoutCreatingRoomsForOrphans() throws Exception {
        migrateTo("3");
        runScript("migration/pre-v4-history-schema.sql");
        runScript("migration/pre-v4-fixtures.sql");

        migrateTo(null);

        assertThat(jdbc.queryForObject("SELECT to_regclass('home_api.room_metadata')::text", String.class)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM home_api.room WHERE room_id = 'ghost'", Integer.class)).isZero();
    }

    @Test
    void historyRoomIdBecomesNullableAfterMigration() throws Exception {
        migrateTo("3");
        runScript("migration/pre-v4-history-schema.sql");

        migrateTo(null);

        jdbc.update("""
                INSERT INTO environment.environment_reading (room_id, sensor_id, sensor_type, observed_at, received_at)
                VALUES (NULL, 'air-9', 'AIR', now(), now())
                """);
        jdbc.update("""
                INSERT INTO occupancy.occupancy_event (room_id, sensor_id, event_type, observed_at, received_at)
                VALUES (NULL, 'door-9', 'OPENING', now(), now())
                """);
        assertThat(count("environment.environment_reading")).isEqualTo(1);
        assertThat(count("occupancy.occupancy_event")).isEqualTo(1);
    }

    @Test
    void migrationOnEmptyHistoryCreatesEmptyDomainTables() throws Exception {
        migrateTo("3");
        runScript("migration/pre-v4-history-schema.sql");

        migrateTo(null);

        assertThat(count("home_api.room")).isZero();
        assertThat(count("home_api.sensor")).isZero();
    }

    @Test
    void renamesAlertColumnsAndKeepsAlertDataAndUniqueness() throws Exception {
        migrateTo("3");
        runScript("migration/pre-v4-history-schema.sql");
        migrateTo("4");
        jdbc.update("""
                INSERT INTO home_api.alert (
                  rule_id, room_id, device_id, severity, status, message, triggered_at,
                  last_evaluated_at, rule_snapshot
                ) VALUES ('high-co2', 'living-room', 'air-1', 'WARNING', 'ACTIVE', 'm', now(), now(), '{}'),
                         ('poor-air', 'living-room', NULL, 'WARNING', 'ACTIVE', 'm', now(), now(), '{}')
                """);
        jdbc.update("""
                INSERT INTO home_api.alert_state (
                  rule_id, room_id, device_key, device_id, condition_active, rule_fingerprint, last_evaluated_at
                ) VALUES ('high-co2', 'living-room', 'air-1', 'air-1', true, 'fp', now()),
                         ('poor-air', 'living-room', '', NULL, true, 'fp', now())
                """);

        migrateTo(null);

        assertThat(columns("home_api", "alert")).contains("sensor_id").doesNotContain("device_id");
        assertThat(columns("home_api", "alert_state"))
                .contains("sensor_id", "sensor_key")
                .doesNotContain("device_id", "device_key");
        assertThat(jdbc.queryForList(
                "SELECT rule_id, COALESCE(sensor_id, '') AS sensor_id FROM home_api.alert ORDER BY rule_id"))
                .extracting(row -> row.get("rule_id") + "|" + row.get("sensor_id"))
                .containsExactly("high-co2|air-1", "poor-air|");
        assertThat(jdbc.queryForObject(
                "SELECT sensor_id FROM home_api.alert_state WHERE sensor_key = 'air-1'", String.class))
                .isEqualTo("air-1");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO home_api.alert (
                  rule_id, room_id, sensor_id, severity, status, message, triggered_at, last_evaluated_at, rule_snapshot
                ) VALUES ('poor-air', 'living-room', NULL, 'WARNING', 'ACTIVE', 'm', now(), now(), '{}')
                """)).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO home_api.alert_state (
                  rule_id, room_id, sensor_key, condition_active, rule_fingerprint, last_evaluated_at
                ) VALUES ('poor-air', 'living-room', '', true, 'fp', now())
                """)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void freshInstallFromPostV5InitSqlMigratesWithoutDeviceId() throws Exception {
        runFile(Path.of("../../deploy/k8s/postgres-init.sql"));
        assertThat(columns("environment", "environment_reading")).doesNotContain("device_id");

        migrateTo(null);

        assertThat(columns("environment", "environment_reading")).contains("sensor_id").doesNotContain("device_id");
        assertThat(columns("occupancy", "occupancy_event")).contains("sensor_id").doesNotContain("device_id");
        assertThat(columns("home_api", "alert")).contains("sensor_id").doesNotContain("device_id");
        assertThat(jdbc.queryForObject("SELECT to_regclass('home_api.room_metadata')::text", String.class)).isNull();
    }

    private List<String> columns(String schema, String table) {
        return jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ?
                """, String.class, schema, table);
    }

    private void runFile(Path path) throws Exception {
        try (Connection connection = scratch.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(path));
        }
    }

    private void migrateTo(String target) {
        var configuration = Flyway.configure()
                .dataSource(scratch)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private void runScript(String classpathResource) throws Exception {
        try (Connection connection = scratch.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(classpathResource));
        }
    }

    private int count(String table) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private static String withDatabase(String jdbcUrl, String database) {
        return jdbcUrl.replaceFirst("(jdbc:postgresql://[^/]+/)[^?]*", "$1" + database);
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
