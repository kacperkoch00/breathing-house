package com.breathinghouse.homeapi.rooms;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @GetMapping("/rooms")
    public RoomsResponse listRooms() {
        return new RoomsResponse(roomService.listRooms());
    }

    @PatchMapping(path = "/rooms/{roomId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RoomSummary rename(
            @PathVariable @NotBlank String roomId,
            @Valid @RequestBody UpdateRoomRequest request) {
        return roomService.rename(roomId, request.requireDisplayNameString());
    }
}
