package com.breathinghouse.homeapi.rooms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

    @Mock
    private RoomRepository roomRepository;

    @Test
    void createGeneratesUuidRoomIdAndPersistsWithClock() {
        when(roomRepository.findById(anyString())).thenAnswer(invocation -> Optional.of(
                new RoomSummary(invocation.getArgument(0), "Living Room", "Open plan", List.of())));

        RoomSummary created = service().createRoom("Living Room", "Open plan");

        ArgumentCaptor<String> roomId = ArgumentCaptor.forClass(String.class);
        verify(roomRepository).insert(roomId.capture(), eq("Living Room"), eq("Open plan"), eq(NOW));
        assertThat(UUID.fromString(roomId.getValue()).toString()).isEqualTo(roomId.getValue());
        assertThat(created.roomId()).isEqualTo(roomId.getValue());
        assertThat(created.sensorIds()).isEmpty();
    }

    @Test
    void createGeneratesDistinctIdsForRoomsWithTheSameName() {
        when(roomRepository.findById(anyString())).thenAnswer(invocation -> Optional.of(
                new RoomSummary(invocation.getArgument(0), "Same", null, List.of())));

        RoomSummary first = service().createRoom("Same", null);
        RoomSummary second = service().createRoom("Same", null);

        assertThat(first.roomId()).isNotEqualTo(second.roomId());
    }

    @Test
    void getRoomThrowsWhenMissing() {
        when(roomRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().getRoom("ghost"))
                .isInstanceOf(RoomNotFoundException.class)
                .hasMessage("Room 'ghost' was not found");
    }

    @Test
    void updateKeepsDescriptionWhenOnlyNameProvided() {
        RoomSummary current = new RoomSummary("room-1", "Old", "Keep me", List.of("air-1"));
        when(roomRepository.findById("room-1")).thenReturn(Optional.of(current));

        service().updateRoom("room-1", new RoomPatch("New", false, null));

        verify(roomRepository).update("room-1", "New", "Keep me", NOW);
    }

    @Test
    void updateKeepsNameWhenOnlyDescriptionProvidedAndAllowsClearing() {
        RoomSummary current = new RoomSummary("room-1", "Old", "Keep me", List.of());
        when(roomRepository.findById("room-1")).thenReturn(Optional.of(current));

        service().updateRoom("room-1", new RoomPatch(null, true, null));

        verify(roomRepository).update(eq("room-1"), eq("Old"), isNull(), eq(NOW));
    }

    @Test
    void updateUnknownRoomDoesNotWrite() {
        when(roomRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateRoom("ghost", new RoomPatch("x", false, null)))
                .isInstanceOf(RoomNotFoundException.class);
        verify(roomRepository, never()).update(any(), any(), any(), any());
    }

    @Test
    void roomFieldsTrimAndEnforceLimits() {
        assertThat(RoomFields.normalizeName("  客厅  ")).isEqualTo("客厅");
        assertThat(RoomFields.normalizeName("x".repeat(100))).hasSize(100);
        assertThatThrownBy(() -> RoomFields.normalizeName("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RoomFields.normalizeName("x".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(RoomFields.normalizeDescription("  hi  ")).isEqualTo("hi");
        assertThat(RoomFields.normalizeDescription("   ")).isNull();
        assertThat(RoomFields.normalizeDescription("d".repeat(500))).hasSize(500);
        assertThatThrownBy(() -> RoomFields.normalizeDescription("d".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private RoomService service() {
        return new RoomService(roomRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
