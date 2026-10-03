package com.breathinghouse.homeapi.rooms;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

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
}
