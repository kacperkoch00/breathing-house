package com.breathinghouse.homeapi.rooms;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@Import({RoomRepository.class, JdbcConfig.class})
@TestPropertySource(properties = {
        "home-api.database.query-timeout-seconds=2"
})
class RoomRepositoryTest {

    @Autowired
    private RoomRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM home_api.room_metadata");
        jdbc.update("DELETE FROM environment.environment_reading");
        jdbc.update("DELETE FROM occupancy.occupancy_event");
    }

    @Test
    void listsRoomPresentOnlyInEnvironmentHistory() {
        insertEnvironment("bedroom");

        assertThat(repository.listRooms())
                .containsExactly(new RoomSummary("bedroom", "bedroom"));
    }

    @Test
    void listsRoomPresentOnlyInOccupancyHistory() {
        insertOccupancy("kitchen");

        assertThat(repository.listRooms())
                .containsExactly(new RoomSummary("kitchen", "kitchen"));
    }

    @Test
    void listsRoomPresentInBothSourcesOnce() {
        insertEnvironment("living-room");
        insertOccupancy("living-room");

        assertThat(repository.listRooms())
                .containsExactly(new RoomSummary("living-room", "living-room"));
    }

    @Test
    void defaultsDisplayNameToRoomIdWithoutMetadata() {
        insertEnvironment("bedroom");
        insertOccupancy("kitchen");

        assertThat(repository.listRooms())
                .containsExactly(
                        new RoomSummary("bedroom", "bedroom"),
                        new RoomSummary("kitchen", "kitchen"));
    }

    @Test
    void usesStoredMetadataOverride() {
        insertEnvironment("living-room");
        repository.upsertDisplayName("living-room", "Living Room", Instant.parse("2026-10-03T10:00:00Z"));

        assertThat(repository.listRooms())
                .containsExactly(new RoomSummary("living-room", "Living Room"));
    }

    @Test
    void ignoresOrphanedMetadata() {
        jdbc.update("""
                INSERT INTO home_api.room_metadata (room_id, display_name, updated_at)
                VALUES ('ghost', 'Ghost Room', ?)
                """, Timestamp.from(Instant.parse("2026-10-03T10:00:00Z")));
        insertEnvironment("bedroom");

        assertThat(repository.listRooms())
                .containsExactly(new RoomSummary("bedroom", "bedroom"));
    }

    @Test
    void ordersRoomsByRoomId() {
        insertEnvironment("living-room");
        insertOccupancy("bedroom");
        insertEnvironment("attic");

        assertThat(repository.listRooms())
                .extracting(RoomSummary::roomId)
                .containsExactly("attic", "bedroom", "living-room");
    }

    @Test
    void firstRenameInsertsMetadataAndLaterRenameUpdatesRow() {
        insertEnvironment("living-room");
        Instant first = Instant.parse("2026-10-03T10:00:00Z");
        Instant second = Instant.parse("2026-10-03T11:00:00Z");

        repository.upsertDisplayName("living-room", "Living Room", first);
        Timestamp firstUpdatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM home_api.room_metadata WHERE room_id = 'living-room'",
                Timestamp.class);

        repository.upsertDisplayName("living-room", "Salon", second);
        Timestamp secondUpdatedAt = jdbc.queryForObject(
                "SELECT updated_at FROM home_api.room_metadata WHERE room_id = 'living-room'",
                Timestamp.class);
        String displayName = jdbc.queryForObject(
                "SELECT display_name FROM home_api.room_metadata WHERE room_id = 'living-room'",
                String.class);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM home_api.room_metadata", Integer.class);

        assertThat(displayName).isEqualTo("Salon");
        assertThat(count).isEqualTo(1);
        assertThat(firstUpdatedAt.toInstant()).isEqualTo(first);
        assertThat(secondUpdatedAt.toInstant()).isEqualTo(second);
        assertThat(secondUpdatedAt).isAfter(firstUpdatedAt);
    }

    @Test
    void acceptsDuplicateDisplayNames() {
        insertEnvironment("bedroom");
        insertEnvironment("office");
        Instant now = Instant.parse("2026-10-03T10:00:00Z");

        repository.upsertDisplayName("bedroom", "Shared Name", now);
        repository.upsertDisplayName("office", "Shared Name", now);

        assertThat(repository.listRooms())
                .containsExactly(
                        new RoomSummary("bedroom", "Shared Name"),
                        new RoomSummary("office", "Shared Name"));
    }

    @Test
    void existsInHistoryReflectsDiscoveredRooms() {
        insertEnvironment("bedroom");

        assertThat(repository.existsInHistory("bedroom")).isTrue();
        assertThat(repository.existsInHistory("missing")).isFalse();
    }

    @Test
    void readinessChecksRoomMetadataTable() {
        assertThat(repository.isReady()).isTrue();
    }

    private void insertEnvironment(String roomId) {
        Instant observed = Instant.parse("2026-10-03T10:00:00Z");
        jdbc.update("""
                INSERT INTO environment.environment_reading (
                  room_id, sensor_type, co2, observed_at, received_at
                ) VALUES (?, 'AIR', 500, ?, ?)
                """, roomId, Timestamp.from(observed), Timestamp.from(observed));
    }

    private void insertOccupancy(String roomId) {
        Instant observed = Instant.parse("2026-10-03T10:00:00Z");
        jdbc.update("""
                INSERT INTO occupancy.occupancy_event (
                  room_id, event_type, present, observed_at, received_at
                ) VALUES (?, 'PRESENCE', true, ?, ?)
                """, roomId, Timestamp.from(observed), Timestamp.from(observed));
    }
}
