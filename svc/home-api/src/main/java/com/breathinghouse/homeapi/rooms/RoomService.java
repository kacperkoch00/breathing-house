package com.breathinghouse.homeapi.rooms;

import com.breathinghouse.homeapi.alerts.AlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class RoomService {

    private final RoomRepository roomRepository;
    private final AlertRepository alertRepository;
    private final Clock clock;

    public RoomService(RoomRepository roomRepository, AlertRepository alertRepository, Clock clock) {
        this.roomRepository = roomRepository;
        this.alertRepository = alertRepository;
        this.clock = clock;
    }

    public List<RoomSummary> listRooms() {
        return roomRepository.listRooms();
    }

    public RoomSummary getRoom(String roomId) {
        return roomRepository.findById(roomId).orElseThrow(() -> new RoomNotFoundException(roomId));
    }

    @Transactional
    public RoomSummary createRoom(String name, String description) {
        String roomId = UUID.randomUUID().toString();
        roomRepository.insert(roomId, name, description, clock.instant());
        return getRoom(roomId);
    }

    @Transactional
    public RoomSummary updateRoom(String roomId, RoomPatch patch) {
        RoomSummary current = getRoom(roomId);
        String name = patch.name() != null ? patch.name() : current.name();
        String description = patch.descriptionProvided() ? patch.description() : current.description();
        roomRepository.update(roomId, name, description, clock.instant());
        return getRoom(roomId);
    }

    /**
     * Hard-delete a room: resolve its active alerts, then delete the row.
     * Sensors are unassigned via FK ON DELETE SET NULL. History rows are kept.
     */
    @Transactional
    public void deleteRoom(String roomId) {
        if (!roomRepository.exists(roomId)) {
            throw new RoomNotFoundException(roomId);
        }
        Instant now = clock.instant();
        alertRepository.resolveAllAlertsInRoom(roomId, now);
        roomRepository.delete(roomId);
    }
}
