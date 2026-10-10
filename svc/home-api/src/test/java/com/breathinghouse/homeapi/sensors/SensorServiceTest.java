package com.breathinghouse.homeapi.sensors;

import com.breathinghouse.homeapi.alerts.SensorReassignmentAlertHandler;
import com.breathinghouse.homeapi.rooms.RoomNotFoundException;
import com.breathinghouse.homeapi.rooms.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SensorServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

    @Mock
    private SensorRepository sensorRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private SensorReassignmentAlertHandler alertHandler;

    private SensorService service;

    @BeforeEach
    void setUp() {
        service = new SensorService(
                sensorRepository, roomRepository, alertHandler, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void pairInsertsUnknownSensor() {
        SensorSummary created = new SensorSummary("air-11", "air-11", List.of(), null);
        when(sensorRepository.findById("air-11")).thenReturn(Optional.empty(), Optional.of(created));
        when(sensorRepository.insertIgnore("air-11", "air-11", NOW)).thenReturn(1);

        PairResult result = service.pair("air-11", null);

        assertThat(result.created()).isTrue();
        assertThat(result.sensor()).isEqualTo(created);
    }

    @Test
    void pairIsIdempotentAndDoesNotOverwriteDisplayName() {
        SensorSummary existing = new SensorSummary("air-11", "Kitchen", List.of("AIR"), null);
        when(sensorRepository.findById("air-11")).thenReturn(Optional.of(existing));

        PairResult result = service.pair("air-11", "Kitchen air");

        assertThat(result.created()).isFalse();
        assertThat(result.sensor().displayName()).isEqualTo("Kitchen");
        verify(sensorRepository, never()).insertIgnore(any(), any(), any());
    }

    @Test
    void unpairDeletesKnownSensor() {
        when(sensorRepository.lockCurrentRoom("air-1")).thenReturn(Optional.of(Optional.empty()));
        when(sensorRepository.delete("air-1")).thenReturn(1);

        service.unpair("air-1");

        verify(sensorRepository).delete("air-1");
        verifyNoInteractions(alertHandler);
    }

    @Test
    void unpairAssignedSensorResolvesAlertsThenDeletes() {
        when(sensorRepository.lockCurrentRoom("air-1")).thenReturn(Optional.of(Optional.of("room-a")));
        when(sensorRepository.delete("air-1")).thenReturn(1);

        service.unpair("air-1");

        InOrder order = inOrder(alertHandler, sensorRepository);
        order.verify(alertHandler).onSensorLeftRoom("air-1", "room-a", null);
        order.verify(sensorRepository).delete("air-1");
    }

    @Test
    void unpairUnknownSensorIsNotFound() {
        when(sensorRepository.lockCurrentRoom("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unpair("ghost"))
                .isInstanceOf(SensorNotFoundException.class);
        verify(sensorRepository, never()).delete(anyString());
    }

    @Test
    void renameUpdatesDisplayNameAndReturnsSensor() {
        SensorSummary renamed = new SensorSummary("air-1", "Kitchen", List.of("AIR"), null);
        when(sensorRepository.updateDisplayName("air-1", "Kitchen", NOW)).thenReturn(1);
        when(sensorRepository.findById("air-1")).thenReturn(Optional.of(renamed));

        assertThat(service.rename("air-1", "Kitchen")).isEqualTo(renamed);
    }

    @Test
    void renameUnknownSensorIsNotFound() {
        when(sensorRepository.updateDisplayName("ghost", "x", NOW)).thenReturn(0);

        assertThatThrownBy(() -> service.rename("ghost", "x"))
                .isInstanceOf(SensorNotFoundException.class)
                .hasMessage("Sensor 'ghost' was not found");
    }

    @Test
    void assignUnassignedSensorDoesNotTouchAlerts() {
        when(roomRepository.exists("room-b")).thenReturn(true);
        when(sensorRepository.lockCurrentRoom("air-1")).thenReturn(Optional.of(Optional.empty()));
        when(sensorRepository.findById("air-1"))
                .thenReturn(Optional.of(new SensorSummary("air-1", "air-1", List.of(), "room-b")));

        SensorSummary result = service.assign("room-b", "air-1");

        assertThat(result.roomId()).isEqualTo("room-b");
        verify(sensorRepository).updateRoom("air-1", "room-b", NOW);
        verifyNoInteractions(alertHandler);
    }

    @Test
    void moveUpdatesAssignmentThenReconcilesAlertsForOldAndNewRoom() {
        when(roomRepository.exists("room-b")).thenReturn(true);
        when(sensorRepository.lockCurrentRoom("air-1")).thenReturn(Optional.of(Optional.of("room-a")));
        when(sensorRepository.findById("air-1"))
                .thenReturn(Optional.of(new SensorSummary("air-1", "air-1", List.of(), "room-b")));

        service.assign("room-b", "air-1");

        InOrder order = inOrder(sensorRepository, alertHandler);
        order.verify(sensorRepository).updateRoom("air-1", "room-b", NOW);
        order.verify(alertHandler).onSensorLeftRoom("air-1", "room-a", "room-b");
    }

    @Test
    void assignToCurrentRoomIsIdempotent() {
        when(roomRepository.exists("room-a")).thenReturn(true);
        when(sensorRepository.lockCurrentRoom("air-1")).thenReturn(Optional.of(Optional.of("room-a")));
        when(sensorRepository.findById("air-1"))
                .thenReturn(Optional.of(new SensorSummary("air-1", "air-1", List.of(), "room-a")));

        assertThat(service.assign("room-a", "air-1").roomId()).isEqualTo("room-a");

        verify(sensorRepository, never()).updateRoom(anyString(), any(), any());
        verifyNoInteractions(alertHandler);
    }

    @Test
    void assignRejectsUnknownRoomBeforeTouchingTheSensor() {
        when(roomRepository.exists("ghost")).thenReturn(false);

        assertThatThrownBy(() -> service.assign("ghost", "air-1"))
                .isInstanceOf(RoomNotFoundException.class);
        verify(sensorRepository, never()).lockCurrentRoom(anyString());
    }

    @Test
    void assignRejectsUnknownSensor() {
        when(roomRepository.exists("room-b")).thenReturn(true);
        when(sensorRepository.lockCurrentRoom("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assign("room-b", "ghost"))
                .isInstanceOf(SensorNotFoundException.class);
        verify(sensorRepository, never()).updateRoom(anyString(), any(), any());
    }

    @Test
    void unassignClearsRoomAndReconcilesAlerts() {
        when(roomRepository.exists("room-a")).thenReturn(true);
        when(sensorRepository.lockCurrentRoom("air-1")).thenReturn(Optional.of(Optional.of("room-a")));
        when(sensorRepository.findById("air-1"))
                .thenReturn(Optional.of(new SensorSummary("air-1", "air-1", List.of(), null)));

        assertThat(service.unassign("room-a", "air-1").roomId()).isNull();

        InOrder order = inOrder(sensorRepository, alertHandler);
        order.verify(sensorRepository).updateRoom("air-1", null, NOW);
        order.verify(alertHandler).onSensorLeftRoom("air-1", "room-a", null);
    }

    @Test
    void unassignFromWrongRoomOrWhenUnassignedIsConflict() {
        when(roomRepository.exists("room-b")).thenReturn(true);
        when(sensorRepository.lockCurrentRoom("air-1")).thenReturn(Optional.of(Optional.of("room-a")));
        when(sensorRepository.lockCurrentRoom("free")).thenReturn(Optional.of(Optional.empty()));

        assertThatThrownBy(() -> service.unassign("room-b", "air-1"))
                .isInstanceOf(SensorAssignmentConflictException.class)
                .hasMessage("Sensor 'air-1' is not currently assigned to room 'room-b'");
        assertThatThrownBy(() -> service.unassign("room-b", "free"))
                .isInstanceOf(SensorAssignmentConflictException.class);
        verify(sensorRepository, never()).updateRoom(anyString(), isNull(), any());
        verifyNoInteractions(alertHandler);
    }

    @Test
    void unassignUnknownRoomOrSensorIsNotFound() {
        when(roomRepository.exists("ghost")).thenReturn(false);
        when(roomRepository.exists("room-a")).thenReturn(true);
        when(sensorRepository.lockCurrentRoom("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unassign("ghost", "air-1"))
                .isInstanceOf(RoomNotFoundException.class);
        assertThatThrownBy(() -> service.unassign("room-a", "ghost"))
                .isInstanceOf(SensorNotFoundException.class);
    }
}
