package com.breathinghouse.homeapi.rooms;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
public class RoomService {

    private final RoomRepository roomRepository;
    private final Clock clock;

    public RoomService(RoomRepository roomRepository, Clock clock) {
        this.roomRepository = roomRepository;
        this.clock = clock;
    }

    public List<RoomSummary> listRooms() {
        return roomRepository.listRooms();
    }

    @Transactional
    public RoomSummary rename(String roomId, String displayName) {
        String trimmed = normalizeDisplayName(displayName);
        if (!roomRepository.existsInHistory(roomId)) {
            throw new RoomNotFoundException(roomId);
        }
        roomRepository.upsertDisplayName(roomId, trimmed, clock.instant());
        return new RoomSummary(roomId, trimmed);
    }

    static String normalizeDisplayName(String displayName) {
        if (displayName == null) {
            throw new IllegalArgumentException("displayName must be provided");
        }
        String trimmed = displayName.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (trimmed.length() > 100) {
            throw new IllegalArgumentException("displayName must be at most 100 characters");
        }
        return trimmed;
    }
}
