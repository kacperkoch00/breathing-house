package com.breathinghouse.homeapi.sensors;

import com.breathinghouse.homeapi.alerts.SensorReassignmentAlertHandler;
import com.breathinghouse.homeapi.rooms.RoomNotFoundException;
import com.breathinghouse.homeapi.rooms.RoomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

@Service
public class SensorService {

    private final SensorRepository sensorRepository;
    private final RoomRepository roomRepository;
    private final SensorReassignmentAlertHandler alertHandler;
    private final Clock clock;

    public SensorService(
            SensorRepository sensorRepository,
            RoomRepository roomRepository,
            SensorReassignmentAlertHandler alertHandler,
            Clock clock) {
        this.sensorRepository = sensorRepository;
        this.roomRepository = roomRepository;
        this.alertHandler = alertHandler;
        this.clock = clock;
    }

    public List<SensorSummary> listSensors() {
        return sensorRepository.listSensors();
    }

    public SensorSummary getSensor(String sensorId) {
        return sensorRepository.findById(sensorId).orElseThrow(() -> new SensorNotFoundException(sensorId));
    }

    @Transactional
    public PairResult pair(String sensorId, String displayName) {
        return sensorRepository.findById(sensorId)
                .map(existing -> new PairResult(existing, false))
                .orElseGet(() -> {
                    String name = displayName == null ? sensorId : displayName;
                    boolean created = sensorRepository.insertIgnore(sensorId, name, clock.instant()) > 0;
                    return new PairResult(getSensor(sensorId), created);
                });
    }

    @Transactional
    public void unpair(String sensorId) {
        Optional<String> roomId = requireCurrentRoom(sensorId);
        roomId.ifPresent(previous -> alertHandler.onSensorLeftRoom(sensorId, previous, null));
        sensorRepository.delete(sensorId);
    }

    @Transactional
    public SensorSummary rename(String sensorId, String displayName) {
        if (sensorRepository.updateDisplayName(sensorId, displayName, clock.instant()) == 0) {
            throw new SensorNotFoundException(sensorId);
        }
        return getSensor(sensorId);
    }

    @Transactional
    public SensorSummary assign(String roomId, String sensorId) {
        requireRoom(roomId);
        Optional<String> previousRoomId = requireCurrentRoom(sensorId);
        if (previousRoomId.filter(roomId::equals).isPresent()) {
            return getSensor(sensorId);
        }

        sensorRepository.updateRoom(sensorId, roomId, clock.instant());
        previousRoomId.ifPresent(previous -> alertHandler.onSensorLeftRoom(sensorId, previous, roomId));
        return getSensor(sensorId);
    }

    @Transactional
    public SensorSummary unassign(String roomId, String sensorId) {
        requireRoom(roomId);
        Optional<String> currentRoomId = requireCurrentRoom(sensorId);
        if (currentRoomId.filter(roomId::equals).isEmpty()) {
            throw new SensorAssignmentConflictException(sensorId, roomId);
        }

        sensorRepository.updateRoom(sensorId, null, clock.instant());
        alertHandler.onSensorLeftRoom(sensorId, roomId, null);
        return getSensor(sensorId);
    }

    private void requireRoom(String roomId) {
        if (!roomRepository.exists(roomId)) {
            throw new RoomNotFoundException(roomId);
        }
    }

    private Optional<String> requireCurrentRoom(String sensorId) {
        return sensorRepository.lockCurrentRoom(sensorId)
                .orElseThrow(() -> new SensorNotFoundException(sensorId));
    }
}
