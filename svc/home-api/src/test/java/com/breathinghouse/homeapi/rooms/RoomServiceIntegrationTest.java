package com.breathinghouse.homeapi.rooms;

import com.breathinghouse.homeapi.config.ClockConfig;
import com.breathinghouse.homeapi.config.JdbcConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@JdbcTest
@Import({RoomRepository.class, RoomService.class, JdbcConfig.class, ClockConfig.class})
@TestPropertySource(properties = {
        "home-api.database.query-timeout-seconds=2"
})
class RoomServiceIntegrationTest {

    @Autowired
    private RoomService roomService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM home_api.room_metadata");
        jdbc.update("DELETE FROM environment.environment_reading");
        jdbc.update("DELETE FROM occupancy.occupancy_event");
    }

    @Test
    void unknownRoomIsRejectedAndNoMetadataRowIsInserted() {
        assertThatThrownBy(() -> roomService.rename("ghost", "Ghost"))
                .isInstanceOf(RoomNotFoundException.class);

        Integer count = jdbc.queryForObject("SELECT count(*) FROM home_api.room_metadata", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void trimsWhitespaceBeforePersistence() {
        Instant observed = Instant.parse("2026-10-03T10:00:00Z");
        jdbc.update("""
                INSERT INTO environment.environment_reading (
                  room_id, sensor_type, co2, observed_at, received_at
                ) VALUES ('living-room', 'AIR', 500, ?, ?)
                """, Timestamp.from(observed), Timestamp.from(observed));

        RoomSummary summary = roomService.rename("living-room", "  Living Room  ");

        assertThat(summary.displayName()).isEqualTo("Living Room");
        String stored = jdbc.queryForObject(
                "SELECT display_name FROM home_api.room_metadata WHERE room_id = 'living-room'",
                String.class);
        assertThat(stored).isEqualTo("Living Room");
    }
}
