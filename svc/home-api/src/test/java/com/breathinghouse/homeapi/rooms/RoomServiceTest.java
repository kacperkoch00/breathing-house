package com.breathinghouse.homeapi.rooms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

    @Mock
    private RoomRepository roomRepository;

    @Test
    void renamesTrimmedDisplayNameForKnownRoom() {
        when(roomRepository.existsInHistory("living-room")).thenReturn(true);
        RoomService service = service();

        RoomSummary summary = service.rename("living-room", "  Living Room  ");

        assertThat(summary).isEqualTo(new RoomSummary("living-room", "Living Room"));
        verify(roomRepository).upsertDisplayName("living-room", "Living Room", NOW);
    }

    @Test
    void rejectsUnknownRoomWithoutInsertingMetadata() {
        when(roomRepository.existsInHistory("ghost")).thenReturn(false);
        RoomService service = service();

        assertThatThrownBy(() -> service.rename("ghost", "Ghost"))
                .isInstanceOf(RoomNotFoundException.class)
                .hasMessage("Room 'ghost' was not found");
        verify(roomRepository, never()).upsertDisplayName(any(), any(), any());
    }

    @Test
    void rejectsBlankAndTooLongDisplayNames() {
        RoomService service = service();

        assertThatThrownBy(() -> service.rename("living-room", "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rename("living-room", "x".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(roomRepository, never()).existsInHistory(any());
    }

    @Test
    void normalizeAcceptsUnicodeAndExactMaxLength() {
        assertThat(RoomService.normalizeDisplayName("  客厅  ")).isEqualTo("客厅");
        assertThat(RoomService.normalizeDisplayName("x".repeat(100))).hasSize(100);
    }

    @Test
    void renameUsesClockInstantForUpdatedAt() {
        when(roomRepository.existsInHistory("bedroom")).thenReturn(true);
        RoomService service = service();

        service.rename("bedroom", "Bedroom");

        ArgumentCaptor<Instant> updatedAt = ArgumentCaptor.forClass(Instant.class);
        verify(roomRepository).upsertDisplayName(eq("bedroom"), eq("Bedroom"), updatedAt.capture());
        assertThat(updatedAt.getValue()).isEqualTo(NOW);
    }

    private RoomService service() {
        return new RoomService(roomRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
