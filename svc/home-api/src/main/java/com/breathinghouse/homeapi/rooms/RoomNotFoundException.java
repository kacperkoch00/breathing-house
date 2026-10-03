package com.breathinghouse.homeapi.rooms;

public class RoomNotFoundException extends RuntimeException {

    private final String roomId;

    public RoomNotFoundException(String roomId) {
        super("Room '" + roomId + "' was not found");
        this.roomId = roomId;
    }

    public String roomId() {
        return roomId;
    }
}
